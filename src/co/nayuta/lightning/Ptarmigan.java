package co.nayuta.lightning;

import com.google.common.util.concurrent.Service;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.KeyChainGroupStructure;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

public class Ptarmigan implements PtarmiganListenerInterface {
    static public final int CHECKUNSPENT_FAIL = -1;
    static public final int CHECKUNSPENT_UNSPENT = 0;
    static public final int CHECKUNSPENT_SPENT = 1;
    //
    static public final int COMMITTXID_LOCAL = 0;
    static public final int COMMITTXID_REMOTE = 1;
    static public final int COMMITTXID_MAX = 2;
    //
    static public final int SPV_START_OK = 0;
    static public final int SPV_START_FILE = 1;
    static public final int SPV_START_BJ = 2;
    static public final int SPV_START_ERR = 3;
    //
    static private final int TIMEOUT_RETRY = 2;
    static private final long TIMEOUT_START = 30;           //sec
    static private final long TIMEOUT_SENDTX = 10000;       //msec
    static private final long TIMEOUT_GET = 30000;          //msec
    //
    private NetworkParameters params;
    private WalletAppKit wak;
    private HashMap<Sha256Hash, Block> blockCache = new HashMap<>();
    private HashMap<Sha256Hash, Transaction> txCache = new HashMap<>();
    private HashMap<String, PtarmiganChannel> mapChannel = new HashMap<>();
    private Sha256Hash creationHash;
    private Logger logger;
    private int blockHeight;

    public class ShortChannelParam {
        int height;
        int bIndex;
        int vIndex;
        byte[] minedHash;       //JNI戻り値専用
        //
        //
        ShortChannelParam() {
            this(0, 0, 0);
        }
        ShortChannelParam(int height, int bIndex, int vIndex) {
            this.height = height;
            this.bIndex = bIndex;
            this.vIndex = vIndex;
            logger.debug("ShortChannelParam()=" + this.toString());
        }
        ShortChannelParam(long shortChannelId) {
            initialize(shortChannelId);
        }
        //
        void initialize(long shortChannelId) {
            logger.debug("initialize()=" + shortChannelId);
            if (shortChannelId != 0) {
                this.height = (int)(shortChannelId >>> 40);
                this.bIndex = (int)((shortChannelId & 0xffffff0000L) >>> 16);
                this.vIndex = (int)(shortChannelId & 0xffff);
            }
            logger.debug("initialize()=" + this.toString());
        }
        //
        @Override
        public String toString() {
            return String.format("height:%d, bIndex:%d, vIndex:%d", height, bIndex, vIndex);
        }
    }

    interface JsonInterface {
        URL getUrl();
        long getFeeratePerKb(Moshi moshi) throws IOException;
    }

    //for Moshi JSON parser
    static class JsonBitcoinFeesEarn implements JsonInterface {
        //https://bitcoinfees.earn.com/api
        //satoshis per byte
        static class JsonParam {
            //int fastestFee;
            //int halfHourFee;
            int hourFee;
        }
        URL url;

        JsonBitcoinFeesEarn() {
            try {
                this.url = new URL("https://bitcoinfees.earn.com/api/v1/fees/recommended");
            } catch (MalformedURLException e) {
                this.url = null;
            }
        }

        @Override
        public URL getUrl() {
            return this.url;
        }

        @Override
        public long getFeeratePerKb(Moshi moshi) throws IOException {
            JsonAdapter<JsonParam> jsonAdapter = moshi.adapter(JsonParam.class);
            JsonParam fee = jsonAdapter.fromJson(getFeeJson(this));
            return fee.hourFee * 1000;
        }
    }
    static class JsonBlockCypher implements JsonInterface {
        //https://www.blockcypher.com/dev/bitcoin/#blockchain
        //satoshis per KB
        static class JsonParam {
            int medium_fee_per_kb;
        }
        URL url;

        JsonBlockCypher() {
            try {
                this.url = new URL("https://api.blockcypher.com/v1/btc/test3");
            } catch (MalformedURLException e) {
                this.url = null;
            }
        }

        @Override
        public URL getUrl() {
            return url;
        }

        @Override
        public long getFeeratePerKb(Moshi moshi) throws IOException {
            JsonAdapter<JsonParam> jsonAdapter = moshi.adapter(JsonParam.class);
            JsonParam fee = jsonAdapter.fromJson(getFeeJson(this));
            return fee.medium_fee_per_kb;
        }
    }
    static class JsonConstantFee implements JsonInterface {
        @Override
        public URL getUrl() {
            return null;
        }

        @Override
        public long getFeeratePerKb(Moshi moshi) {
            return Transaction.DEFAULT_TX_FEE.getValue();
        }
    }

    public Ptarmigan() {
        logger = LoggerFactory.getLogger(this.getClass());

        logger.info("bitcoinj " + VersionMessage.BITCOINJ_VERSION);
    }
    //
    public int spv_start(String pmtProtocolId) {
        logger.info("spv_start: " + pmtProtocolId);
        params = NetworkParameters.fromPmtProtocolID(pmtProtocolId);
        if (params == null) {
            // Error
            logger.error("ERROR: Invalid PmtProtocolID -> $pmtProtocolId");
            return SPV_START_ERR;
        }

        try {
            logger.debug("spv_start: start WalletAppKit");
            wak = new WalletAppKit(params,
                    Script.ScriptType.P2WPKH,
                    KeyChainGroupStructure.DEFAULT,
                    new File("./wallet" + pmtProtocolId), "ptarm_p2wpkh") {
                @Override
                protected void onSetupCompleted() {
                    logger.debug("spv_start: onSetupCompleted");
                    if (!pmtProtocolId.equals(NetworkParameters.PAYMENT_PROTOCOL_ID_REGTEST)) {
                        peerGroup().setUseLocalhostPeerWhenPossible(false);
                    }
                    logger.debug("spv_start: onSetupCompleted - exit");
                    blockHeight = wak.wallet().getLastBlockSeenHeight();
                    System.out.print("\nbegin block download");
                    if (blockHeight != -1) {
                        System.out.print("(" + blockHeight + ")" );
                    }
                }
            };
            if (wak.isChainFileLocked()) {
                logger.error("spv_start: already running");
                return SPV_START_FILE;
            }
            logger.debug("spv_start: startAsync()");
            wak.startAsync();
        } catch (IOException e) {
            logger.error("spv_start: IOException: " + e.getMessage());
            e.printStackTrace();
            return SPV_START_ERR;
        }

        int ret = SPV_START_BJ;
        int retry = TIMEOUT_RETRY;
        while (true) {
            try {
                wak.awaitRunning(TIMEOUT_START, TimeUnit.SECONDS);
                blockHeight = wak.wallet().getLastBlockSeenHeight();

                logger.info("spv_start: balance=" + wak.wallet().getBalance().toFriendlyString());
                logger.info("spv_start: block height=" + blockHeight);
                setCallbackFunctions();
                ret = SPV_START_OK;
                break;
            } catch (TimeoutException e) {
                logger.error("spv_start: TimeoutException: " + e.getMessage());
                logger.error("  " + getStackTrace(e));

                int nowHeight = wak.wallet().getLastBlockSeenHeight();
                logger.debug("spv_start: height=" + nowHeight);
                logger.debug("spv_start: status=" + wak.state().toString());

                if (blockHeight < nowHeight) {
                    logger.info("spv_start: block downloading:" + nowHeight);
                    System.out.print("\n   block downloading(" + nowHeight + ") ");
                    retry = TIMEOUT_RETRY;
                } else {
                    retry--;
                    if (retry <= 0) {
                        logger.error("spv_start: retry out");
                        ret = SPV_START_BJ;
                        break;
                    } else {
                        System.err.println("\nfail download. retry..");
                    }
                }
                blockHeight = nowHeight;
            } catch (Exception e) {
                logger.error("Exception: " + e.getMessage());
                logger.error("  " + getStackTrace(e));
                ret = SPV_START_ERR;
                break;
            }
        }

        logger.info("spv_start - exit");
        if (ret == SPV_START_OK) {
            System.out.println("\nblock downloaded(" + blockHeight + ")");
        } else {
            System.err.println("fail: bitcoinj start");
        }
        return ret;
    }
    //
    private static String getStackTrace(Exception exception) {
        String returnValue = "";
        try (StringWriter error = new StringWriter()) {
            exception.printStackTrace(new PrintWriter(error));
            returnValue = error.toString();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return returnValue;
    }
    //
    public void setCreationHash(byte[] blockHash) {
        creationHash = Sha256Hash.wrapReversed(blockHash);
        logger.debug("setCreationHash()=" + creationHash.toString());
    }
    //
    public int getBlockCount(byte[] blockHash) {
        logger.debug("getBlockCount()");
        blockHeight = wak.wallet().getLastBlockSeenHeight();
        Sha256Hash bhash = wak.wallet().getLastBlockSeenHash();
        logger.debug("  count=" + blockHeight);
        if (blockHash != null) {
            byte[] bhashBytes;
            if (bhash != null) {
                bhashBytes = bhash.getReversedBytes();
                logger.debug("  hash=" + Hex.toHexString(bhashBytes));
            } else {
                logger.debug("  no block hash");
                bhashBytes = Sha256Hash.ZERO_HASH.getBytes();
            }
            System.arraycopy(bhashBytes, 0, blockHash, 0, bhashBytes.length);
        }
        return blockHeight;
    }
    // genesis block hash取得
    public byte[] getGenesisBlockHash() {
        logger.debug("getGenesisBlockHash()");
        Sha256Hash hash = wak.params().getGenesisBlock().getHash();
        logger.debug("  hash=" + hash.toString());
        return hash.getReversedBytes();
    }
    /** confirmation数取得.
     *
     * @param txhash target TXID
     * @return 取得できない場合0を返す
     */
    public int getTxConfirmation(byte[] txhash) {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);
        logger.debug("getTxConfirmation(): txid=" + txHash.toString());

        //debugShowRegisteredChannel();

        //channelとして保持している場合は、その値を返す
        PtarmiganChannel matchChannel = null;
        for (PtarmiganChannel ch : mapChannel.values()) {
            //logger.debug("getTxConfirmation():    " + ch.toString());
            //if (!ch.getMinedBlockHash().equals(Sha256Hash.ZERO_HASH)) {
            //    //
            //    BlockStore bs = wak.chain().getBlockStore();
            //    StoredBlock sb = null;
            //    try {
            //        sb = bs.get(ch.getMinedBlockHash());
            //        logger.debug("height = " + sb.getHeight());
            //        int conf = bs.getChainHead().getHeight() - sb.getHeight() + 1;
            //        if (conf > 0) {
            //            ch.setConfirmation(conf);
            //            mapChannel.put(Hex.toHexString(ch.peerNodeId), ch);
            //            return conf;
            //        }
            //    } catch (BlockStoreException e) {
            //        e.printStackTrace();
            //    }
            //}
            if ((ch.getFundingOutpoint() != null) && ch.getFundingOutpoint().getHash().equals(txHash)) {
                if (ch.getConfirmation() >= 0) {
                    logger.debug("getTxConfirmation:   cached conf=" + ch.getConfirmation());
                    return ch.getConfirmation();
                }
                matchChannel = ch;
                break;
            }
        }
        logger.debug("fail ---> get from block");
        return getTxConfirmationFromBlock(matchChannel, txHash);
    }
    //
    private int getTxConfirmationFromBlock(PtarmiganChannel channel, Sha256Hash txHash) {
        logger.debug("getTxConfirmationFromBlock(): txid=" + txHash.toString());
        if (channel != null) {
            logger.debug("    fundingTxid=" + channel.getFundingOutpoint().toString());
        }

        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        if (blockHash == null) {
            return 0;
        }
        blockHeight = wak.wallet().getLastBlockSeenHeight();
        int c = 0;
        while (true) {
            Block block = getBlockEasy(blockHash);
            if (block == null) {
                break;
            }
            logger.debug("getTxConfirmationFromBlock: blockHash(" + c + ")=" + blockHash.toString());
            List<Transaction> txs = block.getTransactions();
            if (txs != null) {
                int bindex = 0;
                for (Transaction tx0 : txs) {
                    if (tx0.getTxId().equals(txHash)) {
                        if (channel != null) {
                            channel.setMinedBlock(blockHash, blockHeight - c, bindex);
                            channel.setConfirmation(c + 1);
                            mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
                            logger.debug("   update: conf=" + channel.getConfirmation());
                        }
                        logger.debug("getTxConfirmationFromBlock: conf=" + (c + 1));
                        logger.debug("  *bhash=" + block.getHash().toString());
                        logger.debug("  *bindex=" + bindex);
                        logger.debug("  *height=" + (blockHeight - c));
                        logger.debug("  ***" + tx0.toString());
                        return c + 1;
                    }
                    bindex++;
                }
            }
            if (blockHash.equals(creationHash)) {
                break;
            }
            if (blockHash.equals(channel.getLastBlockHash())) {
                break;
            }
            // ひとつ前のブロック
            blockHash = block.getPrevBlockHash();
            c++;
        }
        logger.error("getTxConfirmationFromBlock: fail confirm");
        return 0;
    }
    // short_channel_id計算用パラメータ取得
    public ShortChannelParam getShortChannelParam(byte[] peerId) {
        logger.debug("getShortChannelParam() peerId=" + Hex.toHexString(peerId));
        PtarmiganChannel ch = mapChannel.get(Hex.toHexString(peerId));
        ShortChannelParam param;
        if ((ch != null) && (ch.getConfirmation() != 0)) {
            //bindex取得のためブロックから
            getTxConfirmationFromBlock(ch, ch.getFundingOutpoint().getHash());

            param = ch.getShortChannelId();
            if (param != null) {
                param.minedHash = ch.getMinedBlockHash().getReversedBytes();        //JNI戻り値専用
                logger.debug("  short_channel_param=" + param.toString());
            } else {
                logger.debug("  short_channel_param=null");
            }
        } else {
            logger.debug("  fail: " + ((ch == null) ? "null" : "no short_channel_id"));
            param = null;
        }
        return param;
    }
    // short_channel_idが指すtxid取得(bitcoinj試作：呼ばれない予定)
    public Sha256Hash getTxidFromShortChannelId(long id) {
        logger.debug("getTxidFromShortChannelId(): id=" + id);
        ShortChannelParam shortChannelId = new ShortChannelParam(id);
        int blks = wak.wallet().getLastBlockSeenHeight() - shortChannelId.height + 1;   // 現在のブロックでも1回
        if (blks < 0) {
            return null;
        }
        //
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        Block block = null;
        for (int i = 0; i < blks; i++) {
            block = getBlockEasy(blockHash);
            if (block != null && block.getTransactions() != null) {
                blockHash = block.getPrevBlockHash();
            }
        }
        if (block != null) {
            logger.debug("getTxidFromShortChannelId(): get");
            return block.getTransactions().get(shortChannelId.bIndex).getTxId();
        }
        logger.error("getTxidFromShortChannelId(): fail");
        return null;
    }
    // ブロックから特定のoutpoint(txid,vIndex)をINPUT(vin[0])にもつtxを検索
    public byte[] searchOutPoint(int n, byte[] txhash, int vIndex) {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);
        logger.debug("searchOutPoint(): txid=" + txHash.toString() + ", n=" + n);
        byte[] result = null;
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        if (blockHash == null) {
            logger.error("  searchOutPoint(): fail no blockhash");
            return null;
        }
        logger.debug("  searchOutPoint(): blockhash=" + blockHash.toString() + ", n=" + n);
        Sha256Hash resultHash = null;
        for (int i = 0; i < n; i++) {
            Block blk = getBlockEasy(blockHash);
            if (blk == null || blk.getTransactions() == null) {
                logger.debug("searchOutPoint(): no transactions");
                break;
            }
            logger.debug("searchOutPoint(" + i + "):   blk=" + blk.getHashAsString());
            for (Transaction tx : blk.getTransactions()) {
                TransactionOutPoint outPoint = tx.getInput(0).getOutpoint();
                if (outPoint.getHash().equals(txHash) && outPoint.getIndex() == vIndex) {
                    result = tx.bitcoinSerialize();
                    resultHash = tx.getTxId();
                    break;
                }
            }
            blockHash = blk.getPrevBlockHash();
        }
        logger.debug("searchOutPoint(): result=" + ((result != null) ? resultHash.toString() : "fail"));
        return result;
    }
    //
    public List<byte[]> searchVout(int n, List<byte[]> vOut) {
        logger.debug("searchVout(): n=" + n + ", vOut.size=" + vOut.size());
        List<byte[]> txs = new ArrayList<>();
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        for (int i = 0; i < n; i++) {
            Block blk = getBlockEasy(blockHash);
            if (blk == null || blk.getTransactions() == null) {
                break;
            }
            for (Transaction tx : blk.getTransactions()) {
                TransactionOutput output = tx.getOutput(0);
                byte[] vout_0 = output.getScriptBytes();
                for (byte[] vout : vOut) {
                    if (Arrays.equals(vout, vout_0)) {
                        txs.add(tx.bitcoinSerialize());
                        break;
                    }
                }
            }
            blockHash = blk.getPrevBlockHash();
        }
        logger.debug("  txs=" + txs.size());
        return txs;
    }
    // funding_txを作成
    public byte[] signRawTx(long amount, byte[] scriptPubKey) {
        try {
            long feeRatePerKb = estimateFee();
            logger.debug("signRawTx(): amount=" + amount);
            logger.debug("signRawTx(): feeRatePerKb=" + feeRatePerKb);
            SegwitAddress address = SegwitAddress.fromHash(params, scriptPubKey);
            Coin coin = Coin.valueOf(amount);

            SendRequest req = SendRequest.to(address, coin);
            req.feePerKb = Coin.valueOf(feeRatePerKb);
            wak.wallet().completeTx(req);
            return req.tx.bitcoinSerialize();

        } catch (Exception e) {
            e.printStackTrace();
            logger.warn("exception: " + e.getMessage());
        }
        return null;
    }
    // raw txの展開
    public byte[] sendRawTx(byte[] txData) {
        logger.debug("sendRawTx(): " + Hex.toHexString(txData));
        Transaction tx = new Transaction(params, txData);
        try {
            Transaction txret = wak.peerGroup().broadcastTransaction(tx).future().get(TIMEOUT_SENDTX, TimeUnit.MILLISECONDS);
            logger.debug("sendRawTx(): txid=" + txret.getTxId().toString());
            return txret.getTxId().getReversedBytes();
        } catch (TimeoutException e) {
            logger.warn("TimeoutException");
        } catch (ExecutionException e) {
            logger.warn("ExecutionException: " + e.getCause().getMessage());
            e.getCause().printStackTrace();
        } catch (Exception e) {
            logger.warn("exception: " + e.getMessage());
            e.printStackTrace();
        }
        //mempool check
        Transaction txl = getPeerMempoolTransaction(tx.getTxId());
        if (txl != null) {
            logger.debug("sendRawTx(): mempool txid=" + txl.getTxId().toString());
            return txl.getTxId().getReversedBytes();
        }

        logger.error("sendRawTx(): fail");
        return null;
    }
    // txの展開済みチェック
    public boolean checkBroadcast(byte[] txhash) {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);
        logger.debug("checkBroadcast(): " + txHash.toString());
        if (txCache.containsKey(txHash)) {
            logger.debug("  broadcasted(txCache)");
            return true;
        }
        for (Block block : blockCache.values()) {
            List<Transaction> txs = block.getTransactions();
            if (txs == null) {
                continue;
            }
            for (Transaction tx : txs) {
                if (tx.getTxId().equals(txHash)) {
                    logger.debug("  broadcasted(BlockCache)");
                    return true;
                }
            }
        }
        Transaction tx = getTransaction(txHash);
        logger.debug("  broadcast(get txs)=" + ((tx != null) ? "YES" : "NO"));
        return tx != null;
    }
    // 指定したtxのunspentチェック
    //
    public int checkUnspent(byte[] peerId, byte[] txhash, int vIndex) {
        int retval;
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);
        logger.debug("checkUnspent(): txid=" + txHash.toString() + " : " + vIndex);

        if (peerId != null) {
            logger.debug("    peerId=" + Hex.toHexString(peerId));
            if (!mapChannel.containsKey(Hex.toHexString(peerId))) {
                logger.debug("    not peer");
                return CHECKUNSPENT_FAIL;
            }
            //
            PtarmiganChannel ch = mapChannel.get(Hex.toHexString(peerId));
            retval = checkUnspentChannel(ch, txHash, vIndex);
            if (retval != CHECKUNSPENT_FAIL) {
                logger.debug("  result1=" + retval);
                return retval;
            }
        }

        logger.debug("  check ALL channels");
        for (PtarmiganChannel ch : mapChannel.values()) {
            if (ch == null) {
                continue;
            }
            retval = checkUnspentChannel(ch, txHash, vIndex);
            if (retval != CHECKUNSPENT_FAIL) {
                logger.debug("  result2=" + retval);
                return retval;
            }
        }

        retval = checkUnspentFromBlock(null, txHash, vIndex);
        return retval;
    }
    private int checkUnspentChannel(PtarmiganChannel ch, Sha256Hash txHash, int vIndex) {
        //TransactionOutPoint chkOutpoint = new TransactionOutPoint(params, vIndex, txHash);
        TransactionOutPoint fundingOutpoint = new TransactionOutPoint(params, vIndex, txHash);
        if ((ch.getFundingOutpoint() != null) && ch.getFundingOutpoint().equals(fundingOutpoint)) {
            // funding_tx
            logger.debug("    funding unspent=" + ch.getFundingTxUnspent());
            return ch.getFundingTxUnspent() ? CHECKUNSPENT_UNSPENT : CHECKUNSPENT_SPENT;
        } else {
            // commit_tx
            PtarmiganChannel.CommitTxid commit_tx = ch.getCommitTxid(vIndex);
            if ((commit_tx != null) && (commit_tx.txid != null)) {
                logger.debug("    commit_tx unspent=" + commit_tx.unspent);
                return commit_tx.unspent ? CHECKUNSPENT_UNSPENT : CHECKUNSPENT_SPENT;
            }
        }
        return CHECKUNSPENT_FAIL;
    }
    // 指定したtxのunspentチェック
    //      blockCacheの各blockからvinをチェックする
    private int checkUnspentFromBlock(PtarmiganChannel ch, Sha256Hash txHash, int vIndex) {
        logger.debug("checkUnspentFromBlock(): txid=" + txHash.toString() + " : " + vIndex);
        for (Block block : blockCache.values()) {
            if (block.getTransactions() == null) {
                continue;
            }
            for (Transaction tx : block.getTransactions()) {
                if ((tx != null) && (tx.getInputs() != null)) {
                    for (TransactionInput vin : tx.getInputs()) {
                        if (vin == null) {
                            continue;
                        }
                        TransactionOutPoint pnt = vin.getOutpoint();
                        if ((pnt == null) || (pnt.getHash().equals(Sha256Hash.ZERO_HASH))) {
                            continue;
                        }
                        logger.debug("   input:" + pnt.toString());
                        if (pnt.getHash().equals(txHash) && pnt.getIndex() == vIndex) {
                            logger.debug("      ----> detect!");
                            if (ch != null) {
                                ch.setFundingTxSpent();
                                mapChannel.put(Hex.toHexString(ch.peerNodeId()), ch);
                            }
                            return CHECKUNSPENT_SPENT;
                        }
                    }
                }
            }
        }

        logger.debug("checkUnspentFromBlock(): vin not found");
        return CHECKUNSPENT_UNSPENT;
    }
    // 受信アドレス取得(scriptPubKey)
    public String getNewAddress() {
        try {
            ECKey key = wak.wallet().currentReceiveKey();
            return wak.wallet().currentReceiveAddress().toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "fail";
        }
    }
    // feerate per 1000byte
    public long estimateFee() {
        long returnFeeKb = 0;
        JsonInterface jsonInterface;
        Moshi moshi = new Moshi.Builder().build();
        if (NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET.equals(params.getPaymentProtocolId())) {
            jsonInterface = new JsonBitcoinFeesEarn();
        } else if (NetworkParameters.PAYMENT_PROTOCOL_ID_TESTNET.equals(params.getPaymentProtocolId())) {
            jsonInterface = new JsonBlockCypher();
        } else {
            jsonInterface = new JsonConstantFee();
        }
        try {
            returnFeeKb = jsonInterface.getFeeratePerKb(moshi);
        } catch (IOException e) {
            returnFeeKb = Transaction.DEFAULT_TX_FEE.getValue();
        }
        return returnFeeKb;
    }
    private static String getFeeJson(JsonInterface jsoninf) throws IOException {
        HttpsURLConnection conn = (HttpsURLConnection)jsoninf.getUrl().openConnection();
        conn.setRequestProperty("User-Agent", "ptarmigan");
        //int statusCode = conn.getResponseCode();

        InputStream is = conn.getInputStream();
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader br = new BufferedReader(isr);
        String inputLine;
        StringBuilder jsonBuilder = new StringBuilder();
        while ((inputLine = br.readLine()) != null) {
            jsonBuilder.append(inputLine);
        }
        br.close();
        //logger.debug(jsonBuilder.toString());
        return jsonBuilder.toString();
    }
    // チャネル情報追加
    public void setChannel(
            byte[] peerId,
            long shortChannelId,
            byte[] txid, int vIndex,
            byte[] scriptPubKey,
            byte[] minedHash,
            int lastConfirm,
            byte[]lastHash) {
        logger.debug("setChannel() peerId=" + Hex.toHexString(peerId));
        TransactionOutPoint fundingOutpoint = new TransactionOutPoint(params, vIndex, Sha256Hash.wrapReversed(txid));
        Sha256Hash minedBlockHash = Sha256Hash.wrapReversed(minedHash);
        Sha256Hash lastBlockHash = Sha256Hash.wrapReversed(lastHash);
        //
        //if (!minedBlockHash.equals(Sha256Hash.ZERO_HASH)) {
        //    if (!blockCache.containsKey(minedBlockHash)) {
        //        //cache
        //        getBlockDeep(minedBlockHash);
        //    }
        //}
        int minedHeight = 0;
        try {
            BlockStore bs = wak.chain().getBlockStore();
            StoredBlock sb = bs.get(minedBlockHash);
            if (sb != null) {
                minedHeight = sb.getHeight();
            }
        } catch (BlockStoreException e) {
            e.printStackTrace();
            logger.warn("exception: " + e.getMessage());
        }
        blockHeight = wak.wallet().getLastBlockSeenHeight();

        logger.debug("  shortChannelId=" + shortChannelId);
        logger.debug("  fundingOutpoint=" + fundingOutpoint.toString());
        logger.debug("  scriptPubKey=" + Hex.toHexString(scriptPubKey));
        logger.debug("  minedHash=" + minedBlockHash.toString());
        logger.debug("  minedHeight=" + minedHeight);
        logger.debug("  blockCount =" + blockHeight);
        logger.debug("  lastConfirm=" + lastConfirm);
        logger.debug("  lastHash=" + lastBlockHash.toString());

        byte[] txRaw = null;
        if (minedHeight > 0) {
            //lastConfirmは現在のconfirmationと一致している場合があるため +1する
            txRaw = searchOutPoint(blockHeight - minedHeight + 1 - lastConfirm + 1,
                    fundingOutpoint.getHash().getReversedBytes(), (int)fundingOutpoint.getIndex());
        }
        logger.debug("      " + ((txRaw != null) ? "SPENT" : "UNSPENT"));

        PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
        if (channel == null) {
            logger.debug("    ADD NEW CHANNEL!!");
            channel = new PtarmiganChannel(peerId, new ShortChannelParam());
        } else {
            logger.debug("    change channel settings");
        }
        channel.initialize(shortChannelId, fundingOutpoint, (txRaw == null));
        channel.setMinedBlock(minedBlockHash, minedHeight, -1);
        if (minedHeight > 0) {
            channel.setConfirmation(blockHeight - minedHeight + 1);
        }
        try {
            SegwitAddress address = SegwitAddress.fromHash(params, scriptPubKey);
            wak.wallet().addWatchedAddress(address);
        } catch (Exception e) {
            e.getCause().printStackTrace();
            logger.warn("exception: " + e.getCause().getMessage());
        }
        mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
        logger.debug("add channel: " + Hex.toHexString(peerId));

        if (!channel.getFundingTxUnspent()) {
            checkUnspentFromBlock(channel, fundingOutpoint.getHash(), vIndex);
        }

        debugShowRegisteredChannel();
    }
    //
    // チャネル情報削除
    public void delChannel(byte[] peerId) {
        PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
        if (channel != null) {
            mapChannel.remove(Hex.toHexString(peerId));
            logger.debug("delete channel: " + Hex.toHexString(peerId));
        } else {
            logger.debug("no such channel: " + Hex.toHexString(peerId));
        }
    }
    // 監視tx登録
    public void setCommitTxid(byte[] peerId, int index, int commitNum, Sha256Hash txHash) {
        PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
        if (channel != null) {
            channel.setCommitTxid(index, commitNum, txHash);
            mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
        }
    }
    // balance取得
    public long getBalance() {
        logger.debug("getBalance(): available=" + wak.wallet().getBalance(Wallet.BalanceType.AVAILABLE).getValue());
        logger.debug("             +spendable=" + wak.wallet().getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE).getValue());
        logger.debug("              estimated=" + wak.wallet().getBalance(Wallet.BalanceType.ESTIMATED).getValue());
        logger.debug("             +spendable=" + wak.wallet().getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE).getValue());
        return wak.wallet().getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE).getValue();
    }
    // walletを空にするtxを作成して送信
    public byte[] emptyWallet(String sendAddress) {
        logger.debug("emptyWallet(): sendAddress=" + sendAddress);
        Transaction tx = null;
        try {
            Address address = Address.fromString(params, sendAddress);
            SendRequest sendRequest = SendRequest.emptyWallet(address);
            wak.wallet().completeTx(sendRequest);
            tx = sendRequest.tx;
            Transaction txl = wak.peerGroup().broadcastTransaction(tx).future().get(TIMEOUT_SENDTX, TimeUnit.MILLISECONDS);
            logger.debug("emptyWallet(): txid=" + txl.getTxId().toString());
            return txl.getTxId().getReversedBytes();
        } catch (TimeoutException e) {
            if (tx != null) {
                Transaction txl = getPeerMempoolTransaction(tx.getTxId());
                if (txl != null) {
                    logger.debug("emptyWallet(): mempool txid=" + txl.getTxId().toString());
                    return txl.getTxId().getReversedBytes();
                }
            }
        } catch (ExecutionException e) {
            logger.warn("execution: " + e.getCause().getMessage());
            e.getCause().printStackTrace();
        } catch (Wallet.CouldNotAdjustDownwards e) {
            logger.warn("not enough amount");
        } catch (Exception e) {
            logger.warn("exception: " + e.getMessage());
            e.printStackTrace();
        }
        logger.error("emptyWallet(): fail");
        return null;
    }
    //-------------------------------------------------------------------------
    // Private
    //-------------------------------------------------------------------------
    private void setCallbackFunctions() {
        logger.info("set callbacks");
        wak.peerGroup().addBlocksDownloadedEventListener((peer, block, filteredBlock, blocksLeft) -> {
            logger.debug("  [CB]BlocksDownloaded: " + block.getHash().toString() + "-> left:" + blocksLeft);
            if (filteredBlock != null) {
                logger.debug("                    " + filteredBlock.getHash().toString());
                logger.debug("    txs in filtered block:");
                for (Sha256Hash hash : filteredBlock.getTransactionHashes()) {
                    logger.debug("      txid:" + hash.toString());
                    Transaction tx = filteredBlock.getAssociatedTransactions().get(hash);
                    if (tx != null) {
                        logger.debug(tx.toString());
                        confidenceEvent(tx, filteredBlock.getHash(), true);
                    }
                }
            }
            Optional.ofNullable(block.getTransactions()).ifPresent(txs -> txs.stream().flatMap(tx -> tx.getInputs().stream()).filter(TransactionInput::hasWitness).forEach(txin -> {
                logger.debug("    tx: " + txin.getParentTransaction().getTxId().toString());
                IntStream.range(0, txin.getWitness().getPushCount()).forEach(i ->
                        logger.debug("    wt: " + Arrays.toString(txin.getWitness().getPush(i)))
                );
            }));
        });
        //
        wak.peerGroup().addPreMessageReceivedEventListener(Threading.SAME_THREAD, (peer, m) -> {
            if (m instanceof RejectMessage) {
                RejectMessage rm = (RejectMessage) m;
                logger.debug("  [CB]PreMessageReceived: -> ");
                logger.debug("    reject message: " + rm.getReasonString());
            }
            return m;
        });
        //commit_txの捕捉に使用できる
        wak.peerGroup().addOnTransactionBroadcastListener((peer, tx) -> {
            logger.debug("  [CB]TransactionBroadcast: -> " + tx.getTxId().toString());
            logger.debug("    tx: " + tx);
            sendEvent(tx);
        });
        //
        wak.wallet().addCoinsReceivedEventListener((wallet, tx, coin0, coin1) -> {
            logger.debug("  [CB]CoinsReceived: -> " + tx.getTxId().toString());
            logger.debug("    tx: " + tx);
            logger.debug("    coin0: " + coin0);
            logger.debug("    coin1: " + coin1);
            TransactionOutPoint opnt = tx.getInput(0).getOutpoint();
            logger.debug("      outpoint: " + opnt.getHash().toString() + ":" + opnt.getIndex());
            recvEvent(tx);
        });
        wak.wallet().addCoinsSentEventListener((wallet, tx, coin0, coin1) -> {
            logger.debug("  [CB]CoinsSent: -> " + tx.getTxId().toString());
            logger.debug("    tx: " + tx);
            logger.debug("    coin0: " + coin0);
            logger.debug("    coin1: " + coin1);
            TransactionOutPoint opnt = tx.getInput(0).getOutpoint();
            logger.debug("      outpoint: " + opnt.getHash().toString() + ":" + opnt.getIndex());
            sendEvent(tx);
        });
        wak.wallet().addReorganizeEventListener(wallet -> {
            logger.debug("  [CB]Reorganize: ->");
        });
        wak.wallet().addScriptsChangeEventListener((wallet, scripts, isAdding) -> {
            logger.debug("  [CB]ScriptsChange: -> ");
            logger.debug("    scripts: " + scripts);
        });
        wak.wallet().addKeyChainEventListener(keys -> {
            logger.debug("  [CB]KeyChain: -> ");
        });
        wak.wallet().addTransactionConfidenceEventListener((wallet, tx) -> {
            //miningが複数ブロック行われた場合、その回数だけ呼び出される
            Sha256Hash hash = wallet.getLastBlockSeenHash();
            if (hash != null) {
                logger.debug("  [CB]TransactionConfidence: -> ");
                logger.debug("    tx: "+tx);
                confidenceEvent(tx, hash, false);
            }
        });
        wak.wallet().addChangeEventListener(wallet -> {
            logger.debug("  [CB]WalletChange: -> " + wallet.getBalance().toFriendlyString());
        });
        logger.info("set callbacks: end");
    }

    // Block取得
    private Block getBlockEasy(Sha256Hash blockHash) {
        if (blockCache.containsKey(blockHash)) {
            logger.debug("  getBlockEasy() - blockCache1: " + blockHash.toString());
            return blockCache.get(blockHash);
        } else {
            try {
                Block block = wak.peerGroup().getConnectedPeers().get(0).getBlock(blockHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
                if (block != null) {
                    logger.debug("  getBlockEasy() - blockCache2: " + blockHash.toString());
                    blockCache.put(blockHash, block);
                    return block;
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.warn("exception: " + e.getMessage());
            }
        }
        logger.error("  getBlockEasy() - fail");
        return null;
    }
    // Block順次取得
    private Block getBlockDeep(Sha256Hash searchBlockHash) {
        logger.debug("getBlockDeep(): " + searchBlockHash.toString());
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        while (true) {
            Block block;
            try {
                block = wak.peerGroup().getConnectedPeers().get(0).getBlock(blockHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.error("getBlockDeep(): fail");
                return null;
            }
            // キャッシュ格納
            blockCache.put(block.getHash(), block);
            if (block.getHash().equals(searchBlockHash)) {
                logger.debug("getBlockDeep(): end");
                return block;
            }
            // ひとつ前のブロック
            blockHash = block.getPrevBlockHash();
            //
            if (blockHash.equals(creationHash)) {
                break;
            }
        }
        logger.error("getBlockDeep(): fail");
        return null;
    }
    // Tx取得
    private Transaction getTransaction(Sha256Hash txHash) {
        // Tx Cache
        if (txCache.containsKey(txHash)) {
            return txCache.get(txHash);
        }
        // Block
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        while (true) {
            Block block;
            try {
                block = wak.peerGroup().getConnectedPeers().get(0).getBlock(blockHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return null;
            }
            List<Transaction> txs = block.getTransactions();
            // キャッシュ格納
            blockCache.put(block.getHash(), block);
            if (txs != null) {
                txs.forEach(tx -> txCache.put(tx.getTxId(), tx));
                // 探索
                Optional<Transaction> otx = txs.stream().filter(tx -> tx.getTxId().equals(txHash)).findFirst();
                if (otx.isPresent()) {
                    return otx.get();
                }
            }
            // ひとつ前のブロック
            blockHash = block.getPrevBlockHash();
            //
            if (blockHash.equals(creationHash)) {
                break;
            }
        }
        return null;
    }
    // MempoolからTx取得
    private Transaction getPeerMempoolTransaction(Sha256Hash txHash) {
        try {
            return wak.peerGroup().getConnectedPeers().get(0).getPeerMempoolTransaction(txHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warn("fail: getPeerMempoolTransaction");
            e.printStackTrace();
        }
        return null;
    }
    // 直近のBlock取得
    //private List<Block> getLastNBlocks(int n) {
    //    ArrayList<Block> list = new ArrayList<>();
    //    Sha256Hash hash = wak.wallet().getLastBlockSeenHash();
    //    //
    //    if (n > 0) {
    //        for (int i = 1; i <= n; i++) {
    //            Block block = getBlockEasy(hash);
    //            if (block != null) {
    //                list.add(block);
    //                hash = block.getHash();
    //            }
    //        }
    //    }
    //    return Lists.reverse(list.stream().filter(Objects::nonNull).collect(Collectors.toList()));
    //}
    // 直近BlockからTx取得
    //private List<Transaction> getLastNBlocksTx(int n) {
    //    ArrayList<Transaction> txs = new ArrayList<>();
    //    for (Block block : getLastNBlocks(n)) {
    //        List<Transaction> tx = block.getTransactions();
    //        if (tx != null) {
    //            txs.addAll(tx);
    //        }
    //    }
    //    return txs;
    //}
    // 監視対象Script登録
    //private void addWatchScript(Script script) {
    //    wak.wallet().addWatchedScripts(Collections.singletonList(script));
    //}
    // 着金時処理
    private void recvEvent(Transaction txRecv) {
        findRegisteredTx(txRecv);
    }
    // 送金時処理
    private void sendEvent(Transaction txSend) {
        findRegisteredTx(txSend);
    }
    //
    private void findRegisteredTx(Transaction targetTx) {
        TransactionOutPoint targetOutpointTxid = targetTx.getInput(0).getOutpoint();
        logger.debug("findRegisteredTx(): txid=" + targetOutpointTxid.getHash().toString());
        for (PtarmiganChannel ch : mapChannel.values()) {
            TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
            if (fundingOutpoint == null) {
                continue;
            }
            logger.debug("   ch txid=" + fundingOutpoint.toString());
            boolean detect = false;
            if (targetOutpointTxid.equals(fundingOutpoint)) {
                logger.debug("  funding spent!");
                ch.setFundingTxSpent();
                detect = true;
            } else {
                int idx = checkCommitTxids(ch, targetTx.getTxId());
                if (idx != COMMITTXID_MAX) {
                    switch (idx) {
                    case COMMITTXID_LOCAL:
                        logger.debug("unilateral close: local");
                        break;
                    case COMMITTXID_REMOTE:
                        logger.debug("unilateral close: remote");
                        break;
                    }
                    ch.getCommitTxid(idx).unspent = false;
                    mapChannel.put(Hex.toHexString(ch.peerNodeId()), ch);
                    detect = true;
                }
            }
            if (detect) {
                sendFundingTransactionHandler(ch, targetTx);
            }
        }
    }
    // Confidence時処理
    private void confidenceEvent(Transaction txConf, Sha256Hash blockHash, boolean blockListener) {
        logger.debug("===== confidenceEvent(tx=" + txConf.getTxId().toString() + ", block=" + blockHash.toString() + ", " + blockListener + ")");
        int conf = txConf.getConfidence().getDepthInBlocks();
        for (PtarmiganChannel ch : mapChannel.values()) {
            TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
            if (fundingOutpoint == null) {
                continue;
            }
            logger.debug("   confidenceEvent(): fundingTxid=" + fundingOutpoint.toString());
            if (!fundingOutpoint.getHash().equals(txConf.getTxId())) {
                continue;
            }

            logger.debug("   confidenceEvent(): " + txConf.getTxId().toString() + " conf=" + conf);
            //logger.debug(txConf);
            if (ch.getMinedBlockHash().equals(Sha256Hash.ZERO_HASH)) {
                Block block = getBlockEasy(blockHash);
                if (block == null || !block.hasTransactions()) {
                    logger.debug("   no block");
                    break;
                }
                int bindex = 0;
                List<Transaction> txs = block.getTransactions();
                if (txs == null) {
                    logger.debug("   no transaction");
                    break;
                }
                for (Transaction tx : txs) {
                    logger.debug("   tx=" + tx.getTxId().toString());
                    if (tx.getTxId().equals(fundingOutpoint.getHash())) {
                        blockHeight = wak.wallet().getLastBlockSeenHeight();
                        ch.setMinedBlock(block.getHash(), blockHeight, bindex);
                        confFundingTransactionHandler(ch, tx);
                        break;
                    }
                    bindex++;
                }
            }
            if (!blockListener) {
                ch.setConfirmation(conf);
            }

            mapChannel.put(Hex.toHexString(ch.peerNodeId()), ch);
            logger.debug(" --> " + ch.getConfirmation());
        }
    }
    // Txのspent登録チェック
    private int checkCommitTxids(PtarmiganChannel ch, Sha256Hash txidHash) {
        for (int i = COMMITTXID_LOCAL; i < COMMITTXID_MAX; i++) {
            if ((ch.getCommitTxid(i).txid != null) && ch.getCommitTxid(i).txid.equals(txidHash)) {
                return i;
            }
        }
        return COMMITTXID_MAX;
    }
    //debug
    private void debugShowRegisteredChannel() {
        logger.debug("===== debugShowRegisteredChannel =====");
        for (PtarmiganChannel ch : mapChannel.values()) {
            logger.debug("    * " + Hex.toHexString(ch.peerNodeId()));
            TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
            logger.debug("       fund:" + ((fundingOutpoint != null) ? fundingOutpoint.toString() : "no-fundtx") + ":" + ch.getShortChannelId().vIndex);
        }
        logger.debug("===== debugShowRegisteredChannel: end =====");
    }

    //-------------------------------------------------------------------------
    // EventHandler
    //-------------------------------------------------------------------------
    //
    @Override
    public void confFundingTransactionHandler(PtarmiganChannel channel, Transaction tx) {
        logger.debug("[CONF FUNDING]" + channel.toString());
        logger.debug("                peerId: " + Hex.toHexString(channel.peerNodeId()));
        logger.debug("                conf: " + channel.getConfirmation());
        logger.debug(tx.toString());
    }
    //
    @Override
    public void sendFundingTransactionHandler(PtarmiganChannel channel, Transaction tx) {
        logger.debug("[SEND FUNDING]" + channel.toString());
        logger.debug(tx.toString());
    }
    //
    @Override
    public void confTransactionHandler(PtarmiganChannel channel, Transaction tx) {
        logger.debug("[CONF TX]" + channel.toString());
        logger.debug("          conf: " + channel.getConfirmation());
        logger.debug("          peerId: " + Hex.toHexString(channel.peerNodeId()));
        logger.debug(tx.toString());
    }
    //
    @Override
    public void sendTransactionHandler(PtarmiganChannel channel, Transaction tx) {
        logger.debug("[SEND TX]" + channel.toString());
        logger.debug(tx.toString());
    }
}
