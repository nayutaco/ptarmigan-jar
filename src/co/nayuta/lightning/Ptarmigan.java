package co.nayuta.lightning;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
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
    static private final int TIMEOUT_RETRY = 12;
    static private final long TIMEOUT_START = 5;            //sec
    static private final long TIMEOUT_SENDTX = 10000;       //msec
    static private final long TIMEOUT_GET = 30000;          //msec
    //
    static private final String FILE_STARTUP = "bitcoinj_startup.log";
    //
    private NetworkParameters params;
    private WalletAppKit wak;
    private LinkedHashMap<Sha256Hash, Block> blockCache = new LinkedHashMap<>();
    private HashMap<Sha256Hash, Transaction> txCache = new HashMap<>();
    private HashMap<String, PtarmiganChannel> mapChannel = new HashMap<>();
    private Sha256Hash creationHash;
    private Logger logger;

    public class ShortChannelParam {
        int height;
        int bIndex;
        int vIndex;
        byte[] minedHash;       //JNI戻り値専用
        //
        //
        ShortChannelParam() {
            this(-1, -1, -1);
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
        boolean isAvailable() {
            return (this.height > 0) && (this.bIndex > 0) && (this.vIndex >= 0);
        }
        //
        @Override
        public String toString() {
            return String.format("height:%d, bIndex:%d, vIndex:%d", height, bIndex, vIndex);
        }
    }
    public class SearchOutPointResult {
        int height;
        byte[] tx;
        //
        //
        SearchOutPointResult() {
            this.height = 0;
            tx = null;
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
            return (fee != null) ? fee.hourFee * 1000 : Transaction.DEFAULT_TX_FEE.getValue();
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
            return (fee != null) ? fee.medium_fee_per_kb : Transaction.DEFAULT_TX_FEE.getValue();
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
        saveDownloadLog("begin");
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
                    int blockHeight = wak.wallet().getLastBlockSeenHeight();
                    System.out.print("\nbegin block download");
                    if (blockHeight != -1) {
                        System.out.print("(" + blockHeight + ")");
                    }
                    saveDownloadLog("download");
                    logger.debug("spv_start: onSetupCompleted - exit");
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
            logger.error("  " + getStackTrace(e));
            return SPV_START_ERR;
        }

        int ret;
        int retry = TIMEOUT_RETRY;
        int blockHeight = 0;
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

                int nowHeight = 0;
                try {
                    nowHeight = wak.wallet().getLastBlockSeenHeight();
                    logger.debug("spv_start: height=" + nowHeight);
                    logger.debug("spv_start: status=" + wak.state().toString());
                } catch (Exception e2) {
                    logger.error("spv_start: fail getLastBlockSeenHeight: " + getStackTrace(e2));
                }

                if (blockHeight < nowHeight) {
                    logger.info("spv_start: block downloading:" + nowHeight);
                    System.out.print("\n   block downloading(" + nowHeight + ") ");
                    saveDownloadLog("height=" + nowHeight);
                    retry = TIMEOUT_RETRY;
                } else {
                    retry--;
                    if (retry <= 0) {
                        logger.error("spv_start: retry out");
                        System.out.println("fail download.");
                        ret = SPV_START_BJ;
                        break;
                    } else {
                        logger.warn("spv_start: retry blockHeight=" + blockHeight + ", nowHeight=" + nowHeight);
                    }
                }
                blockHeight = nowHeight;
            } catch (Exception e) {
                logger.error("spv_start: " + getStackTrace(e));
                ret = SPV_START_ERR;
                break;
            }
        }

        logger.info("spv_start - exit");
        if (ret == SPV_START_OK) {
            System.out.println("\nblock downloaded(" + blockHeight + ")");
            saveDownloadLog("OK");
        } else {
            System.err.println("fail: bitcoinj start");
            saveDownloadLog("NG");
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
        int blockHeight = wak.wallet().getLastBlockSeenHeight();
        logger.debug("getBlockCount()  count=" + blockHeight);
        if (blockHash != null) {
            byte[] bhashBytes;
            Sha256Hash bhash = wak.wallet().getLastBlockSeenHash();
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
        Sha256Hash hash = wak.params().getGenesisBlock().getHash();
        logger.debug("getGenesisBlockHash()  hash=" + hash.toString());
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

        PtarmiganChannel matchChannel = null;

        //channelとして保持している場合は、その値を返す
        try {
            for (PtarmiganChannel ch : mapChannel.values()) {
                if ((ch.getFundingOutpoint() != null) &&
                        ch.getFundingOutpoint().getHash().equals(txHash)) {
                    if ((ch.getShortChannelId() != null) && (ch.getShortChannelId().height > 0)) {
                        int conf = wak.wallet().getLastBlockSeenHeight() - ch.getShortChannelId().height + 1;
                        ch.setConfirmation(conf);
                        logger.debug("getTxConfirmation:   cached conf=" + ch.getConfirmation());
                        mapChannel.put(Hex.toHexString(ch.peerNodeId()), ch);
                        return ch.getConfirmation();
                    }
                    matchChannel = ch;
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("getTxConfirmation(): " + getStackTrace(e));
        }
        logger.debug("fail ---> get from block");
        return getTxConfirmationFromBlock(matchChannel, txHash);
    }
    //
    private int getTxConfirmationFromBlock(PtarmiganChannel channel, Sha256Hash txHash) {
        try {
            logger.debug("getTxConfirmationFromBlock(): txid=" + txHash.toString());
            if (channel != null) {
                logger.debug("    fundingTxid=" + channel.getFundingOutpoint().toString());
            } else {
                logger.error("getTxConfirmationFromBlock: no channel");
                return 0;
            }

            Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
            if (blockHash == null) {
                return 0;
            }
            int blockHeight = wak.wallet().getLastBlockSeenHeight();
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
                        if ((tx0 != null) && (tx0.getTxId().equals(txHash))) {
                            channel.setMinedBlockHash(block.getHash(), blockHeight - c, bindex);
                            channel.setConfirmation(c + 1);
                            mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
                            logger.debug("getTxConfirmationFromBlock update: conf=" + channel.getConfirmation());
                            return channel.getConfirmation();
                        }
                        bindex++;
                    }
                }
                if (blockHash.equals(creationHash)) {
                    logger.debug(" stop by creationHash");
                    break;
                }
                if (blockHash.equals(channel.getMinedBlockHash())) {
                    logger.debug(" stop by minedHash");
                    break;
                }
                // ひとつ前のブロック
                blockHash = block.getPrevBlockHash();
                c++;
            }
        } catch (Exception e) {
            logger.error("getTxConfirmationFromBlock: " + getStackTrace(e));
        }
        logger.error("getTxConfirmationFromBlock: fail confirm");
        return 0;
    }
    // short_channel_id計算用パラメータ取得
    public ShortChannelParam getShortChannelParam(byte[] peerId) {
        logger.debug("getShortChannelParam() peerId=" + Hex.toHexString(peerId));
        PtarmiganChannel ch = mapChannel.get(Hex.toHexString(peerId));
        ShortChannelParam param;
        if (ch != null) {
            param = ch.getShortChannelId();
            if (param != null) {
                param.minedHash = ch.getMinedBlockHash().getReversedBytes();
                logger.debug("  short_channel_param=" + param.toString());
            } else {
                logger.debug("  short_channel_param=null");
            }
        } else {
            logger.debug("  fail: no channel");
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
    public SearchOutPointResult searchOutPoint(int n, byte[] txhash, int vIndex) {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);
        logger.debug("searchOutPoint(): txid=" + txHash.toString() + ", n=" + n);
        SearchOutPointResult result = new SearchOutPointResult();
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        if (blockHash == null) {
            logger.error("  searchOutPoint(): fail no blockhash");
            return result;
        }
        logger.debug("  searchOutPoint(): blockhash=" + blockHash.toString() + ", n=" + n);
        int blockcount = wak.wallet().getLastBlockSeenHeight();
        for (int i = 0; i < n; i++) {
            Block blk = getBlockEasy(blockHash);
            if (blk == null || blk.getTransactions() == null) {
                logger.debug("searchOutPoint(): no transactions");
                break;
            }
            logger.debug("searchOutPoint(" + blockcount + "):   blk=" + blk.getHashAsString());
            for (Transaction tx : blk.getTransactions()) {
                TransactionOutPoint outPoint = tx.getInput(0).getOutpoint();
                if (outPoint.getHash().equals(txHash) && outPoint.getIndex() == vIndex) {
                    result.tx = tx.bitcoinSerialize();
                    result.height = blockcount;
                    logger.debug("searchOutPoint(): result=" + tx.getTxId() + ", height=" + result.height);
                    break;
                }
            }
            if (result.tx != null) {
                break;
            }
            blockHash = blk.getPrevBlockHash();
            blockcount--;
        }
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
            logger.error("signRawTx: " + getStackTrace(e));
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
        } catch (Exception e) {
            logger.error("signRawTx: " + getStackTrace(e));
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
    public boolean checkBroadcast(byte[] peerId, byte[] txhash) {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);

        logger.debug("checkBroadcast(): " + txHash.toString());
        logger.debug("    peerId=" + Hex.toHexString(peerId));

        if (!mapChannel.containsKey(Hex.toHexString(peerId))) {
            logger.debug("    unknown peer");
            return false;
        }
        PtarmiganChannel ch = mapChannel.get(Hex.toHexString(peerId));

        if (txCache.containsKey(txHash)) {
            logger.debug("  broadcasted(txCache)");
            return true;
        }
        ArrayList<Sha256Hash> keyList = new ArrayList<>(blockCache.keySet());
        for (int i = keyList.size() - 1; i >= 0; i--) {
            Sha256Hash key = keyList.get(i);
            Block block = blockCache.get(key);
            logger.debug("checkBroadcast:: block=" + block.getHashAsString());
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
            if (block.getHash() == ch.getMinedBlockHash()) {
                logger.debug("  not broadcasted(mined block)");
                return false;
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
                logger.debug("    unknown peer");
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
        ArrayList<Sha256Hash> keyList = new ArrayList<>(blockCache.keySet());
        for (int i = keyList.size() - 1; i >= 0; i--) {
            Sha256Hash key = keyList.get(i);
            Block block = blockCache.get(key);
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
            wak.wallet().currentReceiveKey();
            return wak.wallet().currentReceiveAddress().toString();
        } catch (Exception e) {
            logger.error("getNewAddress: " + getStackTrace(e));
            return "fail";
        }
    }
    // feerate per 1000byte
    public long estimateFee() {
        long returnFeeKb;
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
        } catch (Exception e) {
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

    /**
     *
     * @param peerId channel peer node_id
     * @param shortChannelId short_channel_id
     * @param txid funding transaction TXID
     * @param vIndex funding transaction vout
     * @param scriptPubKey witnessScript
     * @param blockHashBytes (lastConfirm=0)establish starting blockhash / (lastConfirm>0)mined blockhash
     * @param lastConfirm last confirmation(==0:establish starting blockhash)
     */
    public void setChannel(
            byte[] peerId,
            long shortChannelId,
            byte[] txid, int vIndex,
            byte[] scriptPubKey,
            byte[] blockHashBytes,
            int lastConfirm) {
        try {
            logger.debug("setChannel() peerId=" + Hex.toHexString(peerId));
            TransactionOutPoint fundingOutpoint = new TransactionOutPoint(params, vIndex, Sha256Hash.wrapReversed(txid));
            Sha256Hash blockHash = Sha256Hash.wrapReversed(blockHashBytes);
            //
            //if (!blockHash.equals(Sha256Hash.ZERO_HASH)) {
            //    if (!blockCache.containsKey(blockHash)) {
            //        //cache
            //        getBlockDeep(blockHash);
            //    }
            //}
            int minedHeight = 0;
            if (lastConfirm > 0) {
                try {
                    BlockStore bs = wak.chain().getBlockStore();
                    StoredBlock sb = bs.get(blockHash);
                    if (sb != null) {
                        minedHeight = sb.getHeight();
                    }
                } catch (BlockStoreException e) {
                    logger.error("setChannel 1: " + getStackTrace(e));
                }
            }
            int blockHeight = wak.wallet().getLastBlockSeenHeight();

            logger.debug("  shortChannelId=" + shortChannelId);
            logger.debug("  fundingOutpoint=" + fundingOutpoint.toString());
            logger.debug("  scriptPubKey=" + Hex.toHexString(scriptPubKey));
            logger.debug("  lastConfirm=" + lastConfirm);
            if (lastConfirm > 0) {
                logger.debug("  minedBlockHash=" + blockHash.toString());
            } else {
                logger.debug("  establishingBlockHash=" + blockHash.toString());
            }
            logger.debug("  minedHeight=" + minedHeight);
            logger.debug("  blockCount =" + blockHeight);

            byte[] txRaw = null;
            if (minedHeight > 0) {
                //lastConfirmは現在のconfirmationと一致している場合がある。
                //余裕を持たせて+3する。
                SearchOutPointResult resultSearch = searchOutPoint(
                            blockHeight - minedHeight + 1 - lastConfirm + 3,
                            fundingOutpoint.getHash().getReversedBytes(), (int) fundingOutpoint.getIndex());
                txRaw = resultSearch.tx;
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
            channel.setMinedBlockHash(blockHash, minedHeight, -1);
            if (minedHeight > 0) {
                channel.setConfirmation(blockHeight - minedHeight + 1);
            }
            try {
                SegwitAddress address = SegwitAddress.fromHash(params, scriptPubKey);
                wak.wallet().addWatchedAddress(address);
            } catch (Exception e) {
                logger.error("setChannel 2: " + getStackTrace(e));
            }
            mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
            logger.debug("add channel: " + Hex.toHexString(peerId));

            if (!channel.getFundingTxUnspent()) {
                checkUnspentFromBlock(channel, fundingOutpoint.getHash(), vIndex);
            }

            debugShowRegisteredChannel();
        } catch (Exception e) {
            logger.error("setChannel: " + getStackTrace(e));
        }
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
        } catch (Wallet.CouldNotAdjustDownwards e) {
            logger.warn("not enough amount");
        } catch (Exception e) {
            logger.error("setChannel 1: " + getStackTrace(e));
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
                        blockDownloadEvent(tx, filteredBlock.getHash());
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
        //wak.peerGroup().addPreMessageReceivedEventListener(Threading.SAME_THREAD, (peer, m) -> {
        //    logger.debug("  [CB]PreMessageReceived: -> ");
        //    if (m instanceof RejectMessage) {
        //        RejectMessage rm = (RejectMessage) m;
        //        logger.debug("    reject message: " + rm.getReasonString());
        //    }
        //    return m;
        //});
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
            logger.debug("  [CB]TransactionConfidence: -> " + tx.getTxId());
            transactionConfidenceEvent(tx);
        });
        wak.wallet().addChangeEventListener(wallet -> {
            logger.debug("  [CB]WalletChange: -> " + wallet.getBalance().toFriendlyString());
        });
        logger.info("set callbacks: end");
    }

    // save block download logfile
    private void saveDownloadLog(String str) {
        try {
            FileWriter fileWriter = new FileWriter("./logs/" + FILE_STARTUP, false);
            fileWriter.write(str);
            fileWriter.close();
        } catch (IOException e) {
            logger.error("FileWriter:" + str);
        }
    }
    // Block取得
    private Block getBlockEasy(Sha256Hash blockHash) {
        if (blockCache.containsKey(blockHash)) {
            logger.debug("  getBlockEasy() - blockCache1: " + blockHash.toString());
            return blockCache.get(blockHash);
        } else {
            try {
                Peer peer = wak.peerGroup().getDownloadPeer();
                if (peer == null) {
                    logger.error("  getBlockEasy() - peer not found");
                    return null;
                }
                Block block = peer.getBlock(blockHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
                if (block != null) {
                    logger.debug("  getBlockEasy() - blockCache2: " + blockHash.toString());
                    blockCache.put(blockHash, block);
                    return block;
                }
            } catch (Exception e) {
                logger.error("getBlockEasy(): " + getStackTrace(e));
            }
        }
        logger.error("  getBlockEasy() - fail");
        return null;
    }
    // Block順次取得
    //private Block getBlockDeep(Sha256Hash searchBlockHash) {
    //    logger.debug("getBlockDeep(): " + searchBlockHash.toString());
    //    Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
    //    while (true) {
    //        Block block;
    //        try {
    //            Peer peer = wak.peerGroup().getDownloadPeer();
    //            if (peer == null) {
    //                logger.error("  getBlockDeep() - peer not found");
    //                return null;
    //            }
    //            block = peer.getBlock(blockHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
    //        } catch (Exception e) {
    //            logger.error("getBlockDeep(): " + getStackTrace(e));
    //            return null;
    //        }
    //        // キャッシュ格納
    //        blockCache.put(block.getHash(), block);
    //        if (block.getHash().equals(searchBlockHash)) {
    //            logger.debug("getBlockDeep(): end");
    //            return block;
    //        }
    //        // ひとつ前のブロック
    //        blockHash = block.getPrevBlockHash();
    //        //
    //        if (blockHash.equals(creationHash)) {
    //            break;
    //        }
    //    }
    //    logger.error("getBlockDeep(): fail");
    //    return null;
    //}
    // Tx取得
    private Transaction getTransaction(Sha256Hash txHash) {
        // Tx Cache
        if (txCache.containsKey(txHash)) {
            return txCache.get(txHash);
        }
        // Block
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        long loopCount = Long.MAX_VALUE;
        while (true) {
            Block block;
            try {
                Peer peer = wak.peerGroup().getDownloadPeer();
                if (peer == null) {
                    logger.error("  getTransaction() - peer not found");
                    return null;
                }
                block = peer.getBlock(blockHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.error("getTransaction(): " + getStackTrace(e));
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
            loopCount--;
            if (loopCount == 0) {
                logger.error("getTransaction: too many loop");
                break;
            }
        }
        return null;
    }
    // MempoolからTx取得
    private Transaction getPeerMempoolTransaction(Sha256Hash txHash) {
        try {
            Peer peer = wak.peerGroup().getDownloadPeer();
            if (peer == null) {
                logger.error("  getPeerMempoolTransaction() - peer not found");
                return null;
            }
            return peer.getPeerMempoolTransaction(txHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.error("getPeerMempoolTransaction(): " + getStackTrace(e));
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
    //
    private void blockDownloadEvent(Transaction tx, Sha256Hash blockHash) {
        logger.debug("===== blockDownloadEvent(tx=" + tx.getTxId().toString() + ", block=" + blockHash.toString() + ")");
        int conf = tx.getConfidence().getDepthInBlocks();
        if (conf == 0) {
            logger.debug("  conf=0");
            return;
        }
        for (PtarmiganChannel ch : mapChannel.values()) {
            TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
            if (fundingOutpoint == null) {
                continue;
            }
            if (!fundingOutpoint.getHash().equals(tx.getTxId())) {
                continue;
            }

            logger.debug("  fundingTxid=" + fundingOutpoint.toString() + " conf=" + conf);
            if (ch.getConfirmation() <= 0) {
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
                for (Transaction txdata : txs) {
                    if (txdata != null) {
                        logger.debug("   tx=" + txdata.getTxId().toString());
                        if (txdata.getTxId().equals(fundingOutpoint.getHash())) {
                            int blockHeight = txdata.getConfidence().getAppearedAtChainHeight();
                            ch.setMinedBlockHash(block.getHash(), blockHeight, bindex);
                            ch.setConfirmation(conf);
                            confFundingTransactionHandler(ch, txdata);
                            break;
                        }
                    }
                    bindex++;
                }
            }

            mapChannel.put(Hex.toHexString(ch.peerNodeId()), ch);
            logger.debug(" --> " + ch.getConfirmation());
        }
    }
    //
    private void transactionConfidenceEvent(Transaction tx) {
        logger.debug("===== transactionConfidenceEvent(tx=" + tx.getTxId().toString() + ", txid=" + tx.getTxId().toString() + ")");
        int conf = tx.getConfidence().getDepthInBlocks();
        if (conf == 0) {
            logger.debug("  conf=0");
            return;
        }
        for (PtarmiganChannel ch : mapChannel.values()) {
            TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
            if (fundingOutpoint == null) {
                continue;
            }
            if (!fundingOutpoint.getHash().equals(tx.getTxId())) {
                continue;
            }
            ch.setConfirmation(conf);
            mapChannel.put(Hex.toHexString(ch.peerNodeId()), ch);
            logger.debug(" --> " + ch.getConfirmation());
            break;
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
            //TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
            //logger.debug("       fund:" + ((fundingOutpoint != null) ? fundingOutpoint.toString() : "no-fundtx") + ":" + ch.getShortChannelId().vIndex);
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
