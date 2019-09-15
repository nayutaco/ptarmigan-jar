package co.nayuta.lightning;

import com.squareup.moshi.Moshi;
import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChainGroupStructure;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Ptarmigan {
    private static final String VERSION = "0.0.4.x";
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
    static private final String FILE_MNEMONIC = "bitcoinj_mnemonic.txt";
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


    /**************************************************************************
     * result
     **************************************************************************/

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


    /**************************************************************************
     * exception
     **************************************************************************/

    static class PtarmException extends Exception {
        PtarmException(String reason) {
            System.out.println("PtarmException: " + reason);
            System.out.flush();
        }
    }
    //


    /**************************************************************************
     * Ptarmigan
     **************************************************************************/

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


    /**
     *
     * @param pmtProtocolId chain name
     * @return  SPV_START_xxx
     */
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
            saveSeedMnemonic(wak.wallet());
        } else {
            System.err.println("fail: bitcoinj start");
            saveDownloadLog(STARTUPLOG_STOP, "*restart DL");
        }
        return ret;
    }


    /** set block hash to stop searching
     *
     * @param blockHash     wallet creation block hash
     */
    public void setCreationHash(byte[] blockHash) {
        creationHash = Sha256Hash.wrapReversed(blockHash);
        logger.debug("setCreationHash()=" + creationHash.toString());
    }


    /** get block height
     *
     * @param blockHash     (output)current block hash
     * @return  current block height
     * @throws PtarmException   fail
     */
    public int getBlockCount(@Nullable byte[] blockHash) throws PtarmException {
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


    /** get genesis block hash
     *
     * @return  genesis block hash
     */
    public byte[] getGenesisBlockHash() {
        Sha256Hash hash = wak.params().getGenesisBlock().getHash();
        logger.debug("getGenesisBlockHash()  hash=" + hash.toString());
        return hash.getReversedBytes();
    }


    /** get confirmation
     *
     * return cached confirmation or block searched confirmation.
     *
     * @param txhash target TXID
     * @param voutIndex (not -1)funding_tx:index, (-1)not funding_tx
     * @param voutWitProg (funding_tx)outpoint witnessProgram
     * @param amount (funding_tx)outpoint amount
     * @return !0:confirmation, 0:error or fail get confirmation
     * @throws PtarmException peer not found count > PEER_FAIL_COUNT_MAX
     */
    public int getTxConfirmation(byte[] txhash, int voutIndex, byte[] voutWitProg, long amount) throws PtarmException {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);
        logger.debug("getTxConfirmation(): txid=" + txHash.toString());

        PtarmiganChannel matchChannel = getChannelFromFundingTx(txHash);
        if (matchChannel != null) {
            if ((matchChannel.getShortChannelId() != null) && (matchChannel.getShortChannelId().height > 0)) {
                int conf = wak.wallet().getLastBlockSeenHeight() - matchChannel.getShortChannelId().height + 1;
                matchChannel.setConfirmation(conf);
                logger.debug("getTxConfirmation:   cached conf=" + matchChannel.getConfirmation());
                mapChannel.put(Hex.toHexString(matchChannel.peerNodeId()), matchChannel);
                return matchChannel.getConfirmation();
            } else {
                logger.debug("getTxConfirmation(): no short_channel");
            }
        }
        logger.debug("fail ---> get from block");
        return getTxConfirmationFromBlock(matchChannel, txHash, voutIndex, voutWitProg, amount);
    }


    /** get confirmation from block
     *
     * @param channel (not null)target funding_tx, (null)only get confirmation
     * @param txHash outpoint:txid
     * @param voutIndex (not -1)funding_tx:index, (-1)not funding_tx
     * @param voutWitProg: (voutIndex != -1)outpoint:witnessProgram
     * @param amount: (voutIndex != -1)outpoint:amount
     * @return !0:confirmation, 0:error or fail get confirmation
     * @throws PtarmException peer not found count > PEER_FAIL_COUNT_MAX
     */
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
            int conf = 0;
            while (true) {
                Block block = getBlock(blockHash);
                if (block == null) {
                    logger.error("getTxConfirmationFromBlock: fail block");
                    break;
                }
                logger.debug("getTxConfirmationFromBlock: blockHash(conf=" + (conf + 1) + ")=" + blockHash.toString());
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
                                channel.setMinedBlockHash(block.getHash(), blockHeight - conf, bindex);
                                channel.setConfirmation(conf + 1);
                                mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
                                logger.debug("getTxConfirmationFromBlock update: conf=" + channel.getConfirmation());
                                logger.debug("CONF:funding_tx:" + tx0.toString());
                                return channel.getConfirmation();
                            } else {
                                logger.debug("getTxConfirmationFromBlock not channel conf: " + (conf + 1));
                                return conf + 1;
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
                conf++;
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


    /** get short_channel_id parameter
     *
     * @param peerId    peer node_id
     * @return  short_channel_id parameter
     */
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


//    /** short_channel_idが指すtxid取得(bitcoinj試作：呼ばれない予定)
//     *
//     * @param id    short_channel_id
//     * @return  txid
//     * @throws PtarmException   fail
//     */
//    public byte[] getTxidFromShortChannelId(long id) throws PtarmException {
//        logger.debug("getTxidFromShortChannelId(): id=" + id);
//        ShortChannelParam shortChannelId = new ShortChannelParam(id);
//        int blks = wak.wallet().getLastBlockSeenHeight() - shortChannelId.height + 1;   // 現在のブロックでも1回
//        if (blks < 0) {
//            return null;
//        }
//        //
//        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
//        Block block = null;
//        for (int i = 0; i < blks; i++) {
//            block = getBlock(blockHash);
//            if (block != null && block.getTransactions() != null) {
//                blockHash = block.getPrevBlockHash();
//            }
//        }
//        if (block != null) {
//            logger.debug("getTxidFromShortChannelId(): get");
//            return block.getTransactions().get(shortChannelId.bIndex).getTxId().getReversedBytes();
//        }
//        logger.error("getTxidFromShortChannelId(): fail");
//        return null;
//    }


    /** search transaction from outpoint
     *
     * ブロックから特定のoutpoint(txid,vIndex)をINPUT(vin[0])にもつtxを検索
     *
     * @param depth     search block count
     * @param txhash    outpoint txid
     * @param vIndex    outpoint index
     * @return  result
     * @throws PtarmException   fail
     */
    public SearchOutPointResult searchOutPoint(int depth, byte[] txhash, int vIndex) throws PtarmException {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);
        logger.debug("searchOutPoint(): txid=" + txHash.toString() + ", depth=" + depth);
        SearchOutPointResult result = new SearchOutPointResult();
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        if (blockHash == null) {
            logger.error("  searchOutPoint(): fail no blockhash");
            return result;
        }
        logger.debug("  searchOutPoint(): blockhash=" + blockHash.toString() + ", depth=" + depth);
        int blockcount = wak.wallet().getLastBlockSeenHeight();
        for (int i = 0; i < depth; i++) {
            Block blk = getBlock(blockHash);
            if (blk == null || blk.getTransactions() == null) {
                logger.error("searchOutPoint(): fail get block");
                return null;
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


    /** search transaction from vout
     *
     * @param depth     search block count
     * @param vOut      target scriptPubKey
     * @return  txid list
     * @throws PtarmException   fail
     */
    public List<byte[]> searchVout(int depth, List<byte[]> vOut) throws PtarmException {
        logger.debug("searchVout(): depth=" + depth + ", vOut.size=" + vOut.size());
        List<byte[]> txs = new ArrayList<>();
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        for (int i = 0; i < depth; i++) {
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


    /** create signed transaction
     *
     * @param amount    amount
     * @param scriptPubKey  send scriptPubKey
     * @return  transaction or null(fail)
     */
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


    /** send raw transaction
     *
     * @param txData    raw transaction
     * @return  taxid
     * @throws PtarmException   fail
     */
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


    /** txの展開済みチェック
     *
     * @param peerId    peer node_id
     * @param txhash    txid
     * @return  true:broadcasted
     * @throws PtarmException   fail
     */
    public boolean checkBroadcast(byte[] peerId, byte[] txhash) throws PtarmException {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txhash);

        logger.debug("checkBroadcast(): " + txHash.toString());
        logger.debug("    peerId=" + Hex.toHexString(peerId));

        if (!mapChannel.containsKey(Hex.toHexString(peerId))) {
            logger.debug("    unknown peer");
            return false;
        }
        if (txCache.containsKey(txHash)) {
            logger.debug("  broadcasted(cache)");
            return true;
        }

        PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        if (blockHash == null) {
            return false;
        }
        try {
            while (true) {
                Block block = getBlock(blockHash);
                if (block == null) {
                    logger.error("checkBroadcast(): fail block");
                    break;
                }
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
                if (block.getHash().equals(channel.getMinedBlockHash())) {
                    logger.debug("  not broadcasted(mined block)");
                    return false;
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
            }

        } catch (PtarmException e) {
            logger.error("rethrow: " + getStackTrace(e));
            //throw e;
        } catch (Exception e) {
            logger.error("getTxConfirmationFromBlock: " + getStackTrace(e));
        }

        Transaction tx = getTransaction(txHash, channel.getMinedBlockHash());
        logger.debug("  broadcast(get txs)=" + ((tx != null) ? "YES" : "NO"));
        return tx != null;
    }


    /** check whether unspent or not
     *
     * @param peerId    peer node_id
     * @param txhash    outpoint txid
     * @param vIndex    outpoint index
     * @return  CHECKUNSPENT_xxx
     */
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
            PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
            retval = checkUnspentChannel(channel, txHash, vIndex);
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


    /** check unspent from cached channel
     *
     * @param ch        channel
     * @param txHash    outpoint txid
     * @param vIndex    outpoint index
     * @return  CHECKUNSPENT_xxx
     */
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


    /** 指定したtxのunspentチェック
     *      blockCacheの各blockからvinをチェックする
     *
     * @param ch        channel
     * @param txHash    outpoint txid
     * @param vIndex    outpoint index
     * @return  CHECKUNSPENT_xxx
     */
    private int checkUnspentFromBlock(PtarmiganChannel ch, Sha256Hash txHash, int vIndex) {
        logger.debug("checkUnspentFromBlock(): txid=" + txHash.toString() + " : " + vIndex);

        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        if (blockHash == null) {
            return CHECKUNSPENT_FAIL;
        }
        PtarmiganChannel channel = getChannelFromFundingTx(txHash);
        try {
            while (true) {
                Block block = getBlock(blockHash);
                if (block == null) {
                    logger.error("checkUnspentFromBlock: fail block");
                    return CHECKUNSPENT_FAIL;
                }
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

                //check last block
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
            }
        } catch (PtarmException e) {
            logger.error("rethrow: " + getStackTrace(e));
            //throw e;
        } catch (Exception e) {
            logger.error("getTxConfirmationFromBlock: " + getStackTrace(e));
        }

        logger.debug("checkUnspentFromBlock(): vin not found");
        return CHECKUNSPENT_UNSPENT;
    }


    /** 受信アドレス取得(scriptPubKey)
     *
     * @return  address
     */
    public String getNewAddress() {
        try {
            wak.wallet().currentReceiveKey();
            return wak.wallet().currentReceiveAddress().toString();
        } catch (Exception e) {
            logger.error("getNewAddress: " + getStackTrace(e));
            return "fail";
        }
    }


    /** feerate per 1000byte
     *
     * @return  feerate per KB
     */
    public long estimateFee() {
        long returnFeeKb;
        FeeRate.JsonInterface jsonInterface;
        Moshi moshi = new Moshi.Builder().build();
        jsonInterface = new FeeRate.JsonBlockCypher(params.getPaymentProtocolId());
        if (jsonInterface.getUrl() == null) {
            jsonInterface = new FeeRate.JsonConstantFee();
        }
        try {
            returnFeeKb = jsonInterface.getFeeratePerKb(moshi);
        } catch (Exception e) {
            returnFeeKb = Transaction.DEFAULT_TX_FEE.getValue();
        }
        logger.debug("feerate=" + returnFeeKb);
        return returnFeeKb;
    }


    /** チャネル情報追加
     *
     * @param peerId channel peer node_id
     * @param shortChannelId short_channel_id
     * @param txid funding transaction TXID
     * @param vIndex funding transaction vout
     * @param scriptPubKey witnessScript
     * @param blockHashBytes (lastConfirm=0)establish starting blockhash / (lastConfirm>0)mined blockhash
     * @param lastConfirm last confirmation(==0:establish starting blockhash)
     */
    public boolean setChannel(
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
                    } else {
                        logger.error("setChannel: fail StoredBlock");
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
                logger.debug("setChannel: minedConfirm");
                channel.setConfirmation(blockHeight - minedHeight + 1);
            } else if (lastConfirm > 0) {
                logger.debug("setChannel: lastConfirm");
                channel.setConfirmation(lastConfirm);
            } else {
                logger.debug("setChannel: confirm not set");
            }
            try {
                SegwitAddress address = SegwitAddress.fromHash(params, scriptPubKey);
                wak.wallet().addWatchedAddress(address);
            } catch (Exception e) {
                logger.error("setChannel 2: " + getStackTrace(e));
            }
            mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
            logger.debug("setChannel: add channel: " + Hex.toHexString(peerId));

            if (!channel.getFundingTxUnspent()) {
                checkUnspentFromBlock(channel, fundingOutpoint.getHash(), vIndex);
            }

            debugShowRegisteredChannel();
        } catch (Exception e) {
            logger.error("setChannel: " + getStackTrace(e));
        }
        logger.debug("setChannel: exit");
    }


    /** チャネル情報削除
     *
     * @param peerId    peer node_id
     */
    public void delChannel(byte[] peerId) {
        PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
        if (channel != null) {
            mapChannel.remove(Hex.toHexString(peerId));
            logger.debug("delete channel: " + Hex.toHexString(peerId));
        } else {
            logger.debug("no such channel: " + Hex.toHexString(peerId));
        }
    }


//    /** 監視tx登録
//     *
//     * @param peerId    peer node_id
//     * @param index
//     * @param commitNum
//     * @param txHash
//     */
//    public void setCommitTxid(byte[] peerId, int index, int commitNum, Sha256Hash txHash) {
//        PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
//        if (channel != null) {
//            channel.setCommitTxid(index, commitNum, txHash);
//            mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
//        }
//    }


    /** balance取得
     *
     * @return  balance(satoshis)
     */
    public long getBalance() {
        logger.debug("getBalance(): available=" + wak.wallet().getBalance(Wallet.BalanceType.AVAILABLE).getValue());
        logger.debug("             +spendable=" + wak.wallet().getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE).getValue());
        logger.debug("              estimated=" + wak.wallet().getBalance(Wallet.BalanceType.ESTIMATED).getValue());
        logger.debug("             +spendable=" + wak.wallet().getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE).getValue());
        return wak.wallet().getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE).getValue();
    }


    /** walletを空にするtxを作成して送信
     *
     * @param sendAddress   send address
     * @return  txid
     * @throws PtarmException   fail
     */
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

    /** set bitcoinj callback functions
     *
     */
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


    /** save block download logfile
     *
     * @param logPrefix     log filename prefix
     * @param str   log string
     */
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


    /** get funding_tx from channels
     *
     * @param txHash    txid
     * @return  channel
     */
    private PtarmiganChannel getChannelFromFundingTx(Sha256Hash txHash) {
        PtarmiganChannel matchChannel = null;
        try {
            for (PtarmiganChannel ch : mapChannel.values()) {
                if ((ch.getFundingOutpoint() != null) &&
                        ch.getFundingOutpoint().getHash().equals(txHash)) {
                    matchChannel = ch;
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("getChannel(): " + getStackTrace(e));
        }
        return matchChannel;
    }


    /** get Peer
     *
     * @return  peer
     * @throws PtarmException   fail
     */
    private Peer getPeer() throws PtarmException {
        Peer peer = wak.peerGroup().getDownloadPeer();
        if (peer != null) {
            peerFailCount = 0;
        } else {
            peerFailCount++;
            logger.error("  getPeer(count=" + peerFailCount + ") - peer not found");
            if (peerFailCount > PEER_FAIL_COUNT_MAX) {
                throw new PtarmException("getPeer: too many fail peer");
            }
        }
        return peer;
    }


    /** get Block from cache or peer
     *
     * @param blockHash     block hash
     * @return  block
     * @throws PtarmException   fail
     */
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


    /** get block from peer
     *
     * @param blockHash     block hash
     * @return  block
     * @throws PtarmException   fail
     */
    private Block getBlockFromPeer(Sha256Hash blockHash) throws PtarmException {
        Block block = null;
        Peer peer = getPeer();
        if (peer == null) {
            logger.error("  getBlockFromPeer() - peer not found");
            return null;
        }
        try {
            block = peer.getBlock(blockHash).get(TIMEOUT_GET, TimeUnit.MILLISECONDS);
            if (block != null) {
                logger.debug("  getBlockFromPeer() " + blockHash.toString());
                blockCache.put(blockHash, block);
                downloadFailCount = 0;
            }
        } catch (Exception e) {
            downloadFailCount++;
            logger.error("getBlockFromPeer(count=" + downloadFailCount + "): " + getStackTrace(e));
            if (downloadFailCount >= DOWNLOAD_FAIL_COUNT_MAX) {
                //
                throw new PtarmException("getBlockFromPeer: stop SPV: too many fail download");
            }
        }
        return block;
    }


    /** get transaction from cache or peer
     *
     * @param txHash    txid
     * @param minedHash limit block hash
     * @return  transaction
     * @throws PtarmException   fail
     */
    private Transaction getTransaction(Sha256Hash txHash, Sha256Hash minedHash) throws PtarmException {
        // Tx Cache
        logger.debug("getTransaction(): " + txHash);
        if (txCache.containsKey(txHash)) {
            logger.debug("   from cache");
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


    /** MempoolからTx取得
     *
     * @param txHash    txid
     * @return  tranasction
     * @throws PtarmException   fail
     */
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


    /** [event]着金時処理
     *
     * @param txRecv    transaction
     */
    private void recvEvent(Transaction txRecv) {
        findRegisteredTx(txRecv);
    }


    /** [event]送金時処理
     *
     * @param txSend    transaction
     */
    private void sendEvent(Transaction txSend) {
        findRegisteredTx(txSend);
    }


    /**
     *
     * @param targetTx  transaction
     */
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
                //おそらくこの部分は稼働していない(commit_txidを設定しないので)
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


    /**
     *  このタイミングでは引数のblockHashとWallet#getLastBlockSeenHash()は必ずしも一致しない。
     *  すなわち、Wallet#getLastBlockSeenHeight()とも一致しないということである。
     * @param blockHash block hash
     */
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


    /**
     *
     * @param message   reject message
     */
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


    /** Txのspent登録チェック
     *
     * @param ch        channel
     * @param txHash    txid
     * @return  hit commit_txid index or COMMITTXID_MAX(fail)
     */
    private int checkCommitTxids(PtarmiganChannel ch, Sha256Hash txHash) {
        for (int i = COMMITTXID_LOCAL; i < COMMITTXID_MAX; i++) {
            if ((ch.getCommitTxid(i).txid != null) && ch.getCommitTxid(i).txid.equals(txHash)) {
                return i;
            }
        }
        return COMMITTXID_MAX;
    }


    /** debug
     *
     */
    private void debugShowRegisteredChannel() {
        logger.debug("===== debugShowRegisteredChannel =====");
        for (PtarmiganChannel ch : mapChannel.values()) {
            logger.debug("    * " + Hex.toHexString(ch.peerNodeId()));
            //TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
            //logger.debug("       fund:" + ((fundingOutpoint != null) ? fundingOutpoint.toString() : "no-fundtx") + ":" + ch.getShortChannelId().vIndex);
        }
        logger.debug("===== debugShowRegisteredChannel: end =====");
    }


    /**  save mnemonic
     *
     * @param wallet    wallet
     */
    private void saveSeedMnemonic(Wallet wallet) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        if ((seed == null) || (seed.getMnemonicCode() == null)) {
            logger.error("mnemonic null");
            return;
        }
        String mnemonic = Utils.SPACE_JOINER.join(seed.getMnemonicCode());
        try {
            FileWriter fileWriter = new FileWriter("./" + FILE_MNEMONIC, false);
            fileWriter.write(mnemonic);
            fileWriter.close();
        } catch (IOException e) {
            logger.error("FileWriter: "+ getStackTrace(e));
        }
    }


    /** remove saved chain file
     *
     */
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


    /** create trace string
     *
     * @param exception     trace target
     * @return  trace
     */
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
}
