package co.nayuta.lightning;

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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Ptarmigan {
    static public final String VERSION = "0.0.4.x";
    //
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
    static private final long TIMEOUT_REJECT = 2000;        //msec
    static private final long TIMEOUT_GET = 30000;          //msec
    //
    static private final int RETRY_SENDRAWTX = 3;
    //
    static private final String FILE_STARTUP = "bitcoinj_startup.log";
    static private final String WALLET_PREFIX = "ptarm_p2wpkh";
    //
    static private final int STARTUPLOG_CONT = 1;
    static private final int STARTUPLOG_STOP = 2;
    static private final int STARTUPLOG_BLOCK = 3;
    //
    static private final int DOWNLOAD_FAIL_COUNT_MAX = 10;
    static private final int PEER_FAIL_COUNT_MAX = 6;
    //
    private NetworkParameters params;
    private WalletAppKit wak;
    private LinkedHashMap<Sha256Hash, Block> blockCache = new LinkedHashMap<>();
    private HashMap<Sha256Hash, Transaction> txCache = new HashMap<>();
    private HashMap<String, PtarmiganChannel> mapChannel = new HashMap<>();
    private HashMap<Sha256Hash, SendRawTxResult> mapSendTx = new HashMap<>();
    private Sha256Hash creationHash;
    private int downloadFailCount = 0;
    private int peerFailCount = 0;
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
    //
    static class SendRawTxResult {
        enum Result {
            NONE,
            RETRY,
            REJECT
        }
        Result result;
        CountDownLatch latch;
        //
        //
        SendRawTxResult() {
            this.result = Result.NONE;
            this.latch = new CountDownLatch(1);
        }
        void lock() throws InterruptedException {
            if (this.latch.getCount() > 0) {
                this.latch.await(TIMEOUT_REJECT, TimeUnit.MILLISECONDS);
            }
        }
        void unlock() {
            this.latch.countDown();
        }
    }
    //
    static class PtarmException extends Exception {
        public PtarmException() {
            System.out.println("PtarmException");
            System.out.flush();
        }
    }
    //
    interface JsonInterface {
        URL getUrl();
        long getFeeratePerKb(Moshi moshi) throws IOException;
    }

    //for Moshi JSON parser
//    static class JsonBitcoinFeesEarn implements JsonInterface {
//        //https://bitcoinfees.earn.com/api
//        //satoshis per byte
//        static class JsonParam {
//            //int fastestFee;
//            //int halfHourFee;
//            int hourFee;
//        }
//        URL url;
//
//        JsonBitcoinFeesEarn(String protocolId) {
//            try {
//                if (protocolId.equals(NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)) {
//                    this.url = new URL("https://bitcoinfees.earn.com/api/v1/fees/recommended");
//                } else {
//                    //no testnet service
//                    this.url = null;
//                }
//            } catch (MalformedURLException e) {
//                this.url = null;
//            }
//        }
//
//        @Override
//        public URL getUrl() {
//            return this.url;
//        }
//
//        @Override
//        public long getFeeratePerKb(Moshi moshi) throws IOException {
//            JsonAdapter<JsonParam> jsonAdapter = moshi.adapter(JsonParam.class);
//            JsonParam fee = jsonAdapter.fromJson(getFeeJson(this));
//            return (fee != null) ? fee.hourFee * 1000 : Transaction.DEFAULT_TX_FEE.getValue();
//        }
//    }
    static class JsonBlockCypher implements JsonInterface {
        //https://www.blockcypher.com/dev/bitcoin/#blockchain
        //satoshis per KB
        static class JsonParam {
            int medium_fee_per_kb;
        }
        URL url;

        JsonBlockCypher(String protocolId) {
            String url;
            if (protocolId.equals(NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)) {
                url = "https://api.blockcypher.com/v1/btc/main";
            } else if (protocolId.equals(NetworkParameters.PAYMENT_PROTOCOL_ID_TESTNET)) {
                url = "https://api.blockcypher.com/v1/btc/test3";
            } else {
                url = "";
            }
            try {
                this.url = new URL(url);
            } catch (MalformedURLException e) {
                System.out.println("malformedURL");
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
            System.out.println("use constant fee");
            return Transaction.DEFAULT_TX_FEE.getValue();
        }
    }

    public Ptarmigan() {
        logger = LoggerFactory.getLogger(this.getClass());

        logger.info("Version: " + VERSION);
        logger.info("bitcoinj " + VersionMessage.BITCOINJ_VERSION);
        saveDownloadLog(STARTUPLOG_CONT, "SPV:" + VERSION);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            //e.printStackTrace();
        }
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
            saveDownloadLog(STARTUPLOG_CONT, "Blocks..");
            wak = new WalletAppKit(params,
                    Script.ScriptType.P2WPKH,
                    KeyChainGroupStructure.DEFAULT,
                    new File("./wallet" + pmtProtocolId), WALLET_PREFIX) {
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
                logger.error("  trace: " + getStackTrace(e));

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
                    saveDownloadLog(STARTUPLOG_BLOCK, String.valueOf(nowHeight));
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
                if (e.getCause() instanceof IOException) {
                    logger.error("IOException: remove chain file");
                    removeChainFile();
                }
                ret = SPV_START_ERR;
                break;
            }
        }

        logger.info("spv_start - exit");
        if (ret == SPV_START_OK) {
            System.out.println("\nblock downloaded(" + blockHeight + ")");
            saveDownloadLog(STARTUPLOG_CONT, "done.");
        } else {
            System.err.println("fail: bitcoinj start");
            saveDownloadLog(STARTUPLOG_STOP, "*restart DL");
        }
        return ret;
    }
    //
    private void removeChainFile() {
        wak.stopAsync();
        String chainFilename = wak.directory().getAbsolutePath() +
                FileSystems.getDefault().getSeparator() + WALLET_PREFIX + ".spvchain";
        Path chainPathOriginal = Paths.get(chainFilename);
        Path chainPathBackup = Paths.get(chainFilename + ".bak");
        try {
            Files.delete(chainPathBackup);
        } catch (IOException eFile) {
            //
        }
        try {
            Files.move(chainPathOriginal, chainPathBackup);
        } catch (IOException eFile) {
            logger.error("spv_start rename chain: " + getStackTrace(eFile));
        }
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
    public int getBlockCount(byte[] blockHash) throws PtarmException {
        int blockHeight = wak.wallet().getLastBlockSeenHeight();
        logger.debug("getBlockCount()  count=" + blockHeight);
        if (getPeer() == null) {
            logger.error("  getBlockCount() - peer not found");
            blockHeight = 0;
        }
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
    public int getTxConfirmation(byte[] txhash, int voutIndex, byte[] voutWitProg, long amount) throws PtarmException {
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
        return getTxConfirmationFromBlock(matchChannel, txHash, voutIndex, voutWitProg, amount);
    }
    //
    private int getTxConfirmationFromBlock(PtarmiganChannel channel, Sha256Hash txHash, int voutIndex, byte[] voutWitProg, long amount) throws PtarmException {
        try {
            logger.debug("getTxConfirmationFromBlock(): txid=" + txHash.toString());
            if (channel != null) {
                logger.debug("    fundingTxid=" + channel.getFundingOutpoint().toString());
            } else {
                logger.error("getTxConfirmationFromBlock: no channel");
            }

            Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
            if (blockHash == null) {
                return 0;
            }
            int blockHeight = wak.wallet().getLastBlockSeenHeight();
            int c = 0;
            while (true) {
                Block block = getBlock(blockHash);
                if (block == null) {
                    if (downloadFailCount >= DOWNLOAD_FAIL_COUNT_MAX) {
                        //
                        logger.error("stop SPV: too many fail download");
                        throw new PtarmException();
                    }
                    break;
                }
                logger.debug("getTxConfirmationFromBlock: blockHash(conf=" + (c + 1) + ")=" + blockHash.toString());
                List<Transaction> txs = block.getTransactions();
                if (txs != null) {
                    int bindex = 0;
                    for (Transaction tx0 : txs) {
                        if ((tx0 != null) && (tx0.getTxId().equals(txHash))) {
                            // tx0.getConfidence()はnot nullでもdepthが0でしか返ってこなかった。
                            if ((channel != null) && channel.getFundingOutpoint().getHash().equals(tx0.getTxId())) {
                                if (voutIndex != -1) {
                                    //check vout
                                    TransactionOutput vout = tx0.getOutput(voutIndex);
                                    if (vout == null) {
                                        logger.error("getTxConfirmationFromBlock: bad vout index");
                                        return 0;
                                    }
                                    logger.debug("vout: " + vout.toString());
                                    if (vout.getValue().value != amount) {
                                        logger.error("getTxConfirmationFromBlock: bad amount");
                                        return 0;
                                    }
                                    logger.debug("voutScript: " + Hex.toHexString(vout.getScriptBytes()));
                                    if (vout.getScriptBytes().length != 34) {
                                        logger.error("getTxConfirmationFromBlock: bad vout script length");
                                        return 0;
                                    }
                                    if (!Arrays.equals(vout.getScriptBytes(), voutWitProg)) {
                                        logger.error("getTxConfirmationFromBlock: bad vout script");
                                        return 0;
                                    }
                                    logger.debug("vout check OK!");
                                }
                                channel.setMinedBlockHash(block.getHash(), blockHeight - c, bindex);
                                channel.setConfirmation(c + 1);
                                mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
                                logger.debug("getTxConfirmationFromBlock update: conf=" + channel.getConfirmation());
                                logger.debug("CONF:funding_tx:" + tx0.toString());
                                return channel.getConfirmation();
                            } else {
                                logger.debug("getTxConfirmationFromBlock not channel conf: " + (c + 1));
                                return c + 1;
                            }
                        }
                        bindex++;
                    }
                }
                if (blockHash.equals(creationHash)) {
                    logger.debug(" stop by creationHash");
                    break;
                }
                if ((channel != null) && blockHash.equals(channel.getMinedBlockHash())) {
                    logger.debug(" stop by minedHash");
                    break;
                }
                // ひとつ前のブロック
                blockHash = block.getPrevBlockHash();
                c++;
            }
        } catch (PtarmException e) {
            logger.error("rethrow: " + getStackTrace(e));
            throw e;
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
    public Sha256Hash getTxidFromShortChannelId(long id) throws PtarmException {
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
            block = getBlock(blockHash);
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
    public SearchOutPointResult searchOutPoint(int n, byte[] txhash, int vIndex) throws PtarmException {
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
            Block blk = getBlock(blockHash);
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
    public List<byte[]> searchVout(int n, List<byte[]> vOut) throws PtarmException {
        logger.debug("searchVout(): n=" + n + ", vOut.size=" + vOut.size());
        List<byte[]> txs = new ArrayList<>();
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        for (int i = 0; i < n; i++) {
            Block blk = getBlock(blockHash);
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
    public byte[] sendRawTx(byte[] txData) throws PtarmException {
        logger.debug("sendRawTx(): " + Hex.toHexString(txData));
        Transaction tx = new Transaction(params, txData);
        try {
            byte[] result = null;
            SendRawTxResult retSendTx = new SendRawTxResult();
            for (int lp = 0; lp < RETRY_SENDRAWTX; lp++) {
                mapSendTx.put(tx.getTxId(), retSendTx);

                Transaction txret = wak.peerGroup().broadcastTransaction(tx).future().get(TIMEOUT_SENDTX, TimeUnit.MILLISECONDS);
                logger.debug("sendRawTx(): txid=" + txret.getTxId().toString());
                if (!txret.getTxId().equals(tx.getTxId())) {
                    logger.error("sendRawTx(): txid not same");
                    break;
                }

                retSendTx.lock();
                switch (retSendTx.result) {
                    case NONE:
                        logger.info("sendRawTx: OK");
                        result = txret.getTxId().getReversedBytes();
                        lp = RETRY_SENDRAWTX;
                        break;
                    case RETRY:
                        logger.error("sendRawTx: fail retry=" + lp);
                        result = null;
                        break;
                    case REJECT:
                        logger.error("sendRawTx: fail reject");
                        result = null;
                        lp = RETRY_SENDRAWTX;
                        break;
                }
                mapSendTx.remove(txret.getTxId());
            }

            return result;
        } catch (Exception e) {
            logger.error("sendRawTx: " + getStackTrace(e));
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
    public boolean checkBroadcast(byte[] peerId, byte[] txhash) throws PtarmException {
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
            if (block.getHash().equals(ch.getMinedBlockHash())) {
                logger.debug("  not broadcasted(mined block)");
                return false;
            }
        }
        Transaction tx = getTransaction(ch.getMinedBlockHash(), txHash);
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
        jsonInterface = new JsonBlockCypher(params.getPaymentProtocolId());
        if (jsonInterface.getUrl() == null) {
            jsonInterface = new JsonConstantFee();
        }
        try {
            returnFeeKb = jsonInterface.getFeeratePerKb(moshi);
        } catch (Exception e) {
            returnFeeKb = Transaction.DEFAULT_TX_FEE.getValue();
        }
        logger.debug("feerate=" + returnFeeKb);
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
                            fundingOutpoint.getHash().getReversedBytes(), (int)fundingOutpoint.getIndex());
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
            //shortChannelIdが0以外ならheight, bIndex, vIndexが更新される
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
    public byte[] emptyWallet(String sendAddress) throws PtarmException {
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
            blockDownloadEvent(block.getHash());
        });
        //
        wak.peerGroup().addPreMessageReceivedEventListener(Threading.SAME_THREAD, (peer, m) -> {
            //logger.debug("  [CB]PreMessageReceived: -> " + m);
            if (m instanceof InventoryMessage) {
                InventoryMessage im = (InventoryMessage)m;
                logger.debug("  [CB]PreMessageReceived: -> inventory message: " + im);
                for (InventoryItem item: im.getItems()) {
                    logger.debug("  " + item.type + ": " + item.hash);
                }
            }
            if (m instanceof RejectMessage) {
                RejectMessage rm = (RejectMessage)m;
                logger.debug("  [CB]PreMessageReceived: -> reject message: " + rm.getReasonString());
                messageRejectEvent(rm);
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
            logger.debug("  [CB]TransactionConfidence: -> " + tx.getTxId());
        });
        wak.wallet().addChangeEventListener(wallet -> {
            logger.debug("  [CB]WalletChange: -> " + wallet.getBalance().toFriendlyString());
        });
        logger.info("set callbacks: end");
    }

    // save block download logfile
    private void saveDownloadLog(int logPrefix, String str) {
        try {
            FileWriter fileWriter = new FileWriter("./logs/" + FILE_STARTUP, false);
            String prefix;
            switch (logPrefix) {
                case STARTUPLOG_CONT:
                    prefix = "CONT=";
                    break;
                case STARTUPLOG_STOP:
                    prefix = "STOP=";
                    break;
                case STARTUPLOG_BLOCK:
                    prefix = "BLOCK=";
                    break;
                default:
                    prefix = "";
            }
            fileWriter.write(prefix + str);
            fileWriter.close();
        } catch (IOException e) {
            logger.error("FileWriter:" + str);
        }
    }
    // Block取得
    private Block getBlock(Sha256Hash blockHash) throws PtarmException {
        logger.debug("getBlock():" + blockHash);
        if (blockCache.containsKey(blockHash)) {
            logger.debug("  getBlock() - blockCache: " + blockHash.toString());
            return blockCache.get(blockHash);
        } else {
            Block block = getBlockFromPeer(blockHash);
            logger.debug("  getBlock() : " + blockHash.toString());
            return block;
        }
    }
    // Tx取得
    private Transaction getTransaction(Sha256Hash minedHash, Sha256Hash txHash) throws PtarmException {
        // Tx Cache
        logger.debug("getTransaction(): " + txHash);
        if (txCache.containsKey(txHash)) {
            logger.debug("   from hash");
            return txCache.get(txHash);
        }
        // Block
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        long loopCount = Long.MAX_VALUE;
        while (true) {
            Block block = getBlockFromPeer(blockHash);
            if (block == null) {
                logger.error("getTransaction(): fail get block");
                return null;
            }
            List<Transaction> txs = block.getTransactions();
            if (txs != null) {
                txs.forEach(tx -> txCache.put(tx.getTxId(), tx));
                // 探索
                Optional<Transaction> otx = txs.stream().filter(tx -> tx.getTxId().equals(txHash)).findFirst();
                if (otx.isPresent()) {
                    logger.debug("  getTransaction(): " + otx.toString());
                    return otx.get();
                }
            }
            // ひとつ前のブロック
            blockHash = block.getPrevBlockHash();
            //
            if (blockHash.equals(minedHash) || (blockHash.equals(creationHash))) {
                logger.debug("getTransaction(): block limit reach");
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
    private Transaction getPeerMempoolTransaction(Sha256Hash txHash) throws PtarmException {
        logger.debug("getPeerMempoolTransaction(): " + txHash);
        Peer peer = getPeer();
        try {
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
            if (targetOutpointTxid.equals(fundingOutpoint)) {
                logger.debug("  funding spent!");
                ch.setFundingTxSpent();
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
                }
            }
        }
    }
    //
    //このタイミングでは引数のblockHashとWallet#getLastBlockSeenHash()は必ずしも一致しない。
    //すなわち、Wallet#getLastBlockSeenHeight()とも一致しないということである。
    private void blockDownloadEvent(Sha256Hash blockHash) {
        logger.debug("===== blockDownloadEvent(block=" + blockHash.toString() + ")");
//
//        for (PtarmiganChannel ch : mapChannel.values()) {
//            TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
//            if (fundingOutpoint == null) {
//                logger.debug("  no channel");
//                continue;
//            }
//            if (ch.getConfirmation() <= 0) {
//                //mining check
//                logger.debug("  blockDownloadEvent: mining check");
//
//                Block block = getBlock(blockHash);
//                if (block == null || !block.hasTransactions()) {
//                    logger.debug("   no block");
//                    break;
//                }
//                int bindex = 0;
//                List<Transaction> txs = block.getTransactions();
//                if (txs == null) {
//                    logger.debug("   no transaction");
//                    break;
//                }
//                for (Transaction tx: txs) {
//                    if (tx != null) {
//                        logger.debug("   blockDownloadEvent: tx=" + tx.getTxId().toString());
//                        if (tx.getTxId().equals(fundingOutpoint.getHash())) {
//                            if (tx.getConfidence() != null) {
//                                int blockHeight = tx.getConfidence().getAppearedAtChainHeight();
//                                logger.debug("   blockDownloadEvent: height=" + blockHeight);
//                                ch.setMinedBlockHash(blockHash, blockHeight, bindex);
//                                ch.setConfirmation(1);
//                                logger.debug("BLOCK funding_tx:" + tx.toString());
//                            } else {
//                                logger.debug("  blockDownloadEvent: no confidence");
//                            }
//                            break;
//                        }
//                    }
//                    bindex++;
//                }
//            }
//
//            mapChannel.put(Hex.toHexString(ch.peerNodeId()), ch);
//            logger.debug(" --> " + ch.getConfirmation());
//        }
    }
    //
    private void messageRejectEvent(RejectMessage message) {
        logger.debug("messageRejectEvent");
        logger.debug("  " + message.getRejectedObjectHash());
        logger.debug("  " + message.getReasonString());

        if (mapSendTx.containsKey(message.getRejectedObjectHash())) {
            logger.debug("messageRejectEvent: NG: send tx");
            SendRawTxResult retSendTx = mapSendTx.get(message.getRejectedObjectHash());
            retSendTx.result = SendRawTxResult.Result.REJECT;
            mapSendTx.put(message.getRejectedObjectHash(), retSendTx);
            retSendTx.unlock();
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
    //
    private Block getBlockFromPeer(Sha256Hash blockHash) throws PtarmException {
        Block block = null;
        Peer peer = getPeer();
        try {
            if (peer == null) {
                logger.error("  getBlockFromPeer() - peer not found");
                return null;
            }
            block = peer.getBlock(blockHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
            if (block != null) {
                logger.debug("  getBlockFromPeer() " + blockHash.toString());
                blockCache.put(blockHash, block);
                downloadFailCount = 0;
            }
        } catch (Exception e) {
            downloadFailCount++;
            logger.error("getBlockFromPeer(count=" + downloadFailCount + "): " + getStackTrace(e));
        }
        return block;
    }
    //
    private Peer getPeer() throws PtarmException {
        Peer peer = wak.peerGroup().getDownloadPeer();
        if (peer != null) {
            peerFailCount = 0;
        } else {
            peerFailCount++;
            logger.error("  getPeer(count=" + peerFailCount + ") - peer not found");
            if (peerFailCount > PEER_FAIL_COUNT_MAX) {
                throw new PtarmException();
            }
        }
        return peer;
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
}
