package co.nayuta.lightning;

import com.squareup.moshi.Moshi;
import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptChunk;
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
    private static final String VERSION = "0.0.5.x";
    //
    public static final int CHECKUNSPENT_FAIL = -1;
    public static final int CHECKUNSPENT_UNSPENT = 0;
    public static final int CHECKUNSPENT_SPENT = 1;
    //
    public static final int COMMITTXID_LOCAL = 0;
    public static final int COMMITTXID_REMOTE = 1;
    public static final int COMMITTXID_MAX = 2;
    //
    public static final int SPV_START_OK = 0;
    public static final int SPV_START_FILE = 1;
    public static final int SPV_START_BJ = 2;
    public static final int SPV_START_ERR = 3;
    //
    private static final int STARTUPLOG_CONT = 1;
    private static final int STARTUPLOG_STOP = 2;
    private static final int STARTUPLOG_BLOCK = 3;
    //
    private static final int TIMEOUT_RETRY = 12;
    private static final long TIMEOUT_START = 5;            //sec
    private static final long TIMEOUT_SENDTX = 10000;       //msec
    private static final long TIMEOUT_REJECT = 2000;        //msec
    private static final long TIMEOUT_GETBLOCK = 15000;      //msec
    //
    private static final int RETRY_SENDRAWTX = 3;
    private static final int RETRY_GETBLOCK = 10;
    private static final int MAX_DOWNLOAD_FAIL = PeerGroup.DEFAULT_CONNECTIONS * 2;
    private static final int MAX_PEER_FAIL = 6;
    private static final int MAX_HEIGHT_FAIL = 50;
    private static final int OFFSET_CHECK_UNSPENT = 5;  //少し多めにチェックする
    //
    private static final String FILE_STARTUP = "bitcoinj_startup.log";
    private static final String FILE_MNEMONIC = "bitcoinj_mnemonic.txt";
    private static final String WALLET_PREFIX = "ptarm_p2wpkh";
    //
    private NetworkParameters params;
    private WalletAppKit wak;
    private LinkedHashMap<Sha256Hash, Block> blockCache = new LinkedHashMap<>();
    private HashMap<Sha256Hash, Transaction> txCache = new HashMap<>();
    private HashMap<String, PtarmiganChannel> mapChannel = new HashMap<>();
    private HashMap<Sha256Hash, SendRawTxResult> mapSendTx = new HashMap<>();
    private Sha256Hash creationHash;
    private int connectedPeerIndex = 0;
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
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            //e.printStackTrace();
        }
    }


    /////////////////////////////////////////////////////////////////////////

    /** initialize blockchain
     *
     * @param pmtProtocolId     chain name
     * @return SPV_START_xxx
     */
    public int spv_start(String pmtProtocolId) {
        logger.info("spv_start: " + pmtProtocolId);
        params = NetworkParameters.fromPmtProtocolID(pmtProtocolId);
        if (params == null) {
            // Error
            logger.error("ERROR: Invalid PmtProtocolID -> $pmtProtocolId");
            return SPV_START_ERR;
        }

        int ret = spv_start_setup(pmtProtocolId);
        if (ret != SPV_START_OK) {
            logger.error("spv_start - reject");
            return ret;
        }

        ret = spv_start_download();
        if (ret == SPV_START_OK) {
            System.out.println("\nblock downloaded");
            saveDownloadLog(STARTUPLOG_CONT, "done.");
            saveSeedMnemonic();
        } else {
            System.err.println("fail: bitcoinj start");
            saveDownloadLog(STARTUPLOG_STOP, "*restart DL");
        }
        logger.info("spv_start - exit");
        return ret;
    }


    /** spv_start(): 1. setup
     *
     * @param pmtProtocolId     chain name
     * @return  SPV_START_xxx
     */
    private int spv_start_setup(String pmtProtocolId) {
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
        return SPV_START_OK;
    }


    /** spv_start(): 2. block download
     *
     * @return SPV_START_xxx
     */
    private int spv_start_download() {
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
        return ret;
    }


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


    /** [event]着金時処理
     *
     * @param tx transaction
     */
    private void recvEvent(Transaction tx) {
        findRegisteredTx(tx);
    }


    /** [event]送金時処理
     *
     * @param tx transaction
     */
    private void sendEvent(Transaction tx) {
        findRegisteredTx(tx);
    }


    /** [event]
     *
     * @param tx transaction
     */
    private void findRegisteredTx(Transaction tx) {
        TransactionOutPoint targetOutpointTxid = tx.getInput(0).getOutpoint();
        logger.debug("findRegisteredTx(): txid=" + targetOutpointTxid.getHash().toString());
        for (PtarmiganChannel ch : mapChannel.values()) {
            TransactionOutPoint fundingOutpoint = ch.getFundingOutpoint();
            if (fundingOutpoint == null) {
                continue;
            }
            logger.debug("   ch txid=" + fundingOutpoint.toString());
            if (targetOutpointTxid.equals(fundingOutpoint)) {
                logger.debug("findRegisteredTx() ----> SPENT funding_tx!");
                ch.setFundingTxSpent();
            } else {
                //おそらくこの部分は稼働していない(commit_txidを設定しないので)
                int index = checkCommitTxids(ch, tx.getTxId());
                if (index != COMMITTXID_MAX) {
                    switch (index) {
                    case COMMITTXID_LOCAL:
                        logger.debug("unilateral close: local");
                        break;
                    case COMMITTXID_REMOTE:
                        logger.debug("unilateral close: remote");
                        break;
                    }
                    ch.getCommitTxid(index).unspent = Ptarmigan.CHECKUNSPENT_SPENT;
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


    /** save block download logfile
     *
     * @param logPrefix log filename prefix
     * @param message log string
     */
    private void saveDownloadLog(int logPrefix, String message) {
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
            fileWriter.write(prefix + message);
            fileWriter.close();
        } catch (IOException e) {
            logger.error("FileWriter:" + message);
        }
    }


    /**  save mnemonic
     */
    private void saveSeedMnemonic() {
        DeterministicSeed seed = wak.wallet().getKeyChainSeed();
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


    /////////////////////////////////////////////////////////////////////////

    /** set block hash to stop searching
     *
     * @param blockHash     wallet creation block hash
     */
    public void setCreationHash(byte[] blockHash) {
        creationHash = Sha256Hash.wrapReversed(blockHash);
        logger.debug("setCreationHash()=" + creationHash.toString());
    }


    /////////////////////////////////////////////////////////////////////////

    /** get block height
     *
     * @param blockHash     (output)current block hash
     * @return  current block height
     * @throws PtarmException   fail
     */
    public int getBlockCount(@Nullable byte[] blockHash) throws PtarmException {
        int blockHeight = wak.wallet().getLastBlockSeenHeight();
        logger.debug("getBlockCount(): count=" + blockHeight);
        if (getPeer() == null) {
            logger.error("getBlockCount(): peer not found");
            blockHeight = 0;
        }
        if (blockHash != null) {
            byte[] bhashBytes;
            Sha256Hash bhash = wak.wallet().getLastBlockSeenHash();
            if (bhash != null) {
                bhashBytes = bhash.getReversedBytes();
                logger.debug("getBlockCount(): hash=" + bhash.toString());
            } else {
                logger.debug("getBlockCount(): no block hash");
                bhashBytes = Sha256Hash.ZERO_HASH.getBytes();
            }
            System.arraycopy(bhashBytes, 0, blockHash, 0, bhashBytes.length);
        }
        return blockHeight;
    }


    /////////////////////////////////////////////////////////////////////////

    /** get genesis block hash
     *
     * @return  genesis block hash
     */
    public byte[] getGenesisBlockHash() {
        Sha256Hash hash = wak.params().getGenesisBlock().getHash();
        logger.debug("getGenesisBlockHash(): hash=" + hash.toString());
        return hash.getReversedBytes();
    }


    /////////////////////////////////////////////////////////////////////////

    /** get confirmation
     *
     * 1. if already confirmed channel, return [current block height] - [short_channel_id height] + 1
     * 2. count confirmation from block
     *
     * @param txid target TXID
     * @param vIndex (not -1)funding_tx:index, (-1)not funding_tx
     * @param witnessProgram (funding_tx)outpoint witnessProgram
     * @param amount (funding_tx)outpoint amount
     * @return !0:confirmation, 0:error or fail get confirmation
     * @throws PtarmException peer not found count > PEER_FAIL_COUNT_MAX
     */
    public int getTxConfirmation(byte[] txid, int vIndex, byte[] witnessProgram, long amount) throws PtarmException {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txid);
        logger.debug("getTxConfirmation(): txid=" + txHash.toString() + ", vIndex=" + vIndex);

        PtarmiganChannel channel = getChannelFromFundingTx(txHash);
        if (channel != null) {
            if ((channel.getShortChannelId() != null) && (channel.getShortChannelId().height > 0)) {
                // already confirmed ==> calculation from current block height
                int conf = wak.wallet().getLastBlockSeenHeight() - channel.getShortChannelId().height + 1;
                channel.setConfirmation(conf);
                logger.debug("getTxConfirmation:   cached conf=" + channel.getConfirmation());
                mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
                return channel.getConfirmation();
            } else {
                logger.debug("getTxConfirmation(): no short_channel");
            }
        }
        logger.debug("getTxConfirmation(): get from block");
        return getTxConfirmationFromBlock(channel, txHash, vIndex, witnessProgram, amount);
    }


    /** get confirmation from block
     *
     * @param channel (not null)target funding_tx, (null)only get confirmation
     * @param txHash outpoint:txid
     * @param vIndex (not -1)funding_tx:index, (-1)not funding_tx
     * @param witnessProgram: (vIndex != -1)outpoint:witnessProgram
     * @param amount: (vIndex != -1)outpoint:amount
     * @return !0:confirmation, 0:error or fail get confirmation
     * @throws PtarmException peer not found count > PEER_FAIL_COUNT_MAX
     */
    private int getTxConfirmationFromBlock(
            PtarmiganChannel channel,
            Sha256Hash txHash, int vIndex,
            byte[] witnessProgram, long amount) throws PtarmException {
        logger.debug("getTxConfirmationFromBlock(): txid=" + txHash.toString() + ", vIndex=" + vIndex);
        Sha256Hash minedHash;
        if (channel != null) {
            minedHash = channel.getMinedBlockHash();
            if (Sha256Hash.ZERO_HASH.equals(minedHash)) {
                logger.error("getTxConfirmationFromBlock(): minedHash=ZERO");
                return 0;
            }
            logger.debug("    fundingTxid=" + channel.getFundingOutpoint().toString());
        } else {
            minedHash = null;
            logger.error("getTxConfirmationFromBlock: no channel");
        }
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        if (blockHash == null) {
            logger.error("getTxConfirmationFromBlock: fail block1");
            return 0;
        }

        try {
            int blockHeight = wak.wallet().getLastBlockSeenHeight();
            int conf = 0;
            while (true) {
                Block block = getBlock(blockHash);
                if (block == null) {
                    logger.error("getTxConfirmationFromBlock: fail block2");
                    break;
                }
                logger.debug("getTxConfirmationFromBlock: blockHash(conf=" + (conf + 1) + ")=" + blockHash.toString());
                List<Transaction> txs = block.getTransactions();
                if (txs != null) {
                    int blockIndex = 0;
                    for (Transaction tx0 : txs) {
                        if ((tx0 != null) && (tx0.getTxId().equals(txHash))) {
                            if ((channel != null) && channel.getFundingOutpoint().getHash().equals(tx0.getTxId())) {
                                if (!getTxConfirmationCheck(tx0, vIndex, witnessProgram, amount)) {
                                    return 0;
                                }
                                return getTxConfirmationChannel(channel, block, blockIndex, blockHeight, conf);
                            } else {
                                logger.debug("getTxConfirmationFromBlock(): not channel conf=" + (conf + 1));
                                return conf + 1;
                            }
                        }
                        blockIndex++;
                    }
                }
                if (blockHash.equals(creationHash)) {
                    logger.debug(" stop by creationHash");
                    break;
                }
                if (blockHash.equals(minedHash)) {
                    logger.debug(" stop by minedHash");
                    break;
                }
                // ひとつ前のブロック
                blockHash = block.getPrevBlockHash();
                conf++;
            }
        } catch (PtarmException e) {
            logger.error("getTxConfirmationFromBlock: rethrow: " + getStackTrace(e));
            throw e;
        } catch (Exception e) {
            logger.error("getTxConfirmationFromBlock(): " + getStackTrace(e));
        }
        logger.error("getTxConfirmationFromBlock: fail confirm");
        return 0;
    }


    private boolean getTxConfirmationCheck(Transaction tx, int vIndex, byte[] witnessProgram, long amount) {
        if (vIndex == -1) {
            //no check
            return true;
        }
        TransactionOutput vout = tx.getOutput(vIndex);
        if (vout == null) {
            logger.error("getTxConfirmationFromBlock: bad vout index");
            return false;
        }
        logger.debug("vout: " + vout.toString());
        if (vout.getValue().value != amount) {
            logger.error("getTxConfirmationFromBlock: bad amount");
            return false;
        }
        logger.debug("voutScript: " + Hex.toHexString(vout.getScriptBytes()));
        if (vout.getScriptBytes().length != 34) {
            logger.error("getTxConfirmationFromBlock: bad vout script length");
            return false;
        }
        if (!Arrays.equals(vout.getScriptBytes(), witnessProgram)) {
            logger.error("getTxConfirmationFromBlock: bad vout script");
            return false;
        }
        return true;
    }


    private int getTxConfirmationChannel(PtarmiganChannel channel, Block block, int blockIndex, int blockHeight, int conf) {
        channel.setMinedBlockHash(block.getHash(), blockHeight - conf, blockIndex);
        channel.setConfirmation(conf + 1);
        mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
        logger.debug("getTxConfirmationFromBlock update: conf=" + channel.getConfirmation());
        return channel.getConfirmation();
    }

    /////////////////////////////////////////////////////////////////////////

    /** get short_channel_id parameter
     *
     * @param peerId    peer node_id
     * @return  short_channel_id parameter
     */
    public ShortChannelParam getShortChannelParam(byte[] peerId) {
        logger.debug("getShortChannelParam() peerId=" + Hex.toHexString(peerId));
        PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
        ShortChannelParam param;
        if (channel != null) {
            param = channel.getShortChannelId();
            if (param != null) {
                param.minedHash = channel.getMinedBlockHash().getReversedBytes();
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


//    /////////////////////////////////////////////////////////////////////////
//
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


    /////////////////////////////////////////////////////////////////////////

    /** search transaction from outpoint
     *
     * ブロックから特定のoutpoint(txid,vIndex)をINPUT(vin[0])にもつtxを検索
     *
     * @param depth     search block count
     * @param txid    outpoint txid
     * @param vIndex    outpoint index
     * @return  result
     * @throws PtarmException   fail
     */
    public SearchOutPointResult searchOutPoint(int depth, byte[] txid, int vIndex) throws PtarmException {
        TransactionOutPoint outPoint = new TransactionOutPoint(params, vIndex, Sha256Hash.wrapReversed(txid));
        logger.debug("searchOutPoint(): outPoint=" + outPoint.toString() + ", depth=" + depth);
        SearchOutPointResult result = new SearchOutPointResult();
        Sha256Hash blockHash = wak.wallet().getLastBlockSeenHash();
        if (blockHash == null) {
            logger.error("  searchOutPoint(): fail no blockhash");
            return result;
        }
        logger.debug("searchOutPoint(): blockhash=" + blockHash.toString() + ", depth=" + depth);
        int blockcount = wak.wallet().getLastBlockSeenHeight();
        for (int i = 0; i < depth; i++) {
            Block blk = getBlock(blockHash);
            if (blk == null || blk.getTransactions() == null) {
                logger.error("searchOutPoint(): fail get block");
                return null;
            }
            logger.debug("searchOutPoint(" + blockcount + "):   blk=" + blk.getHashAsString());
            for (Transaction tx : blk.getTransactions()) {
                if (outPoint.equals(tx.getInput(0).getOutpoint())) {
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


    /////////////////////////////////////////////////////////////////////////

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
                logger.error("searchVout(): fail block");
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


    /////////////////////////////////////////////////////////////////////////

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
            logger.error("signRawTx(): " + getStackTrace(e));
        }
        return null;
    }


    /////////////////////////////////////////////////////////////////////////

    /** send raw transaction
     *
     * @param txData    raw transaction
     * @return  txid
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


    /////////////////////////////////////////////////////////////////////////

    /** txの展開済みチェック
     *
     * @param peerId    peer node_id
     * @param txid    txid
     * @return  true:broadcasted
     * @throws PtarmException   fail
     */
    public boolean checkBroadcast(byte[] peerId, byte[] txid) throws PtarmException {
        Sha256Hash txHash = Sha256Hash.wrapReversed(txid);

        logger.debug("checkBroadcast(): " + txHash.toString());
        logger.debug("    peerId=" + Hex.toHexString(peerId));

        if (!mapChannel.containsKey(Hex.toHexString(peerId))) {
            logger.error("    unknown peer");
            return false;
        }
        if (txCache.containsKey(txHash)) {
            logger.debug("  broadcasted(cache)");
            return true;
        }

        PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
        if (Sha256Hash.ZERO_HASH.equals(channel.getMinedBlockHash())) {
            logger.error("checkBroadcast(): minedHash=ZERO");
            return false;
        }
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
                    logger.error("checkBroadcast(): fail block txs");
                    break;
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
            logger.error("checkBroadcast rethrow: " + getStackTrace(e));
            //throw e;
        } catch (Exception e) {
            logger.error("checkBroadcast: " + getStackTrace(e));
        }

        Transaction tx = getTransaction(txHash, channel.getMinedBlockHash());
        logger.debug("checkBroadcast:  broadcast(get txs)=" + ((tx != null) ? "YES" : "NO"));
        return tx != null;
    }


    /////////////////////////////////////////////////////////////////////////

    /** check whether unspent or not
     *
     * @param peerId    peer node_id(for limit block height)
     * @param txid    outpoint txid
     * @param vIndex    outpoint index
     * @return  CHECKUNSPENT_xxx
     */
    public int checkUnspent(byte[] peerId, byte[] txid, int vIndex) {
        int retval;
        TransactionOutPoint outPoint = new TransactionOutPoint(params, vIndex, Sha256Hash.wrapReversed(txid));
        logger.debug("checkUnspent(): outPoint=" + outPoint.toString());
        boolean isFundingTx = false;
        PtarmiganChannel channel = null;

        if (peerId != null) {
            logger.debug("    peerId=" + Hex.toHexString(peerId));
            if (!mapChannel.containsKey(Hex.toHexString(peerId))) {
                logger.debug("    unknown peer");
                return CHECKUNSPENT_FAIL;
            }
            channel = mapChannel.get(Hex.toHexString(peerId));
            if (channel != null) {
                if (Sha256Hash.ZERO_HASH.equals(channel.getMinedBlockHash())) {
                    logger.error("checkUnspent(): minedHash=ZERO");
                    return CHECKUNSPENT_FAIL;
                }
                isFundingTx = channel.isFundingTx(outPoint);
                retval = checkUnspentChannel(channel, outPoint);
                if (retval != CHECKUNSPENT_FAIL) {
                    logger.debug("checkUnspent(): from channel=" + checkUnspentString(retval));
                    return retval;
                }
            }
        }

        logger.debug("  check ALL channels");
        for (PtarmiganChannel ch : mapChannel.values()) {
            if (ch == null) {
                continue;
            }
            retval = checkUnspentChannel(ch, outPoint);
            if (retval != CHECKUNSPENT_FAIL) {
                logger.debug("checkUnspent(): from ALL channel=" + checkUnspentString(retval));
                return retval;
            }
        }

        // search until wallet creation time
        retval = checkUnspentFromBlock(null, outPoint);
        if (isFundingTx) {
            channel.setFundingTxSpentValue(retval);
            mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
        }
        return retval;
    }


    /** check unspent from cached channel
     *
     * @param channel   channel
     * @param outPoint  outpoint
     * @return  CHECKUNSPENT_xxx
     */
    private int checkUnspentChannel(PtarmiganChannel channel, TransactionOutPoint outPoint) {
        if ((channel.getFundingOutpoint() != null) && channel.getFundingOutpoint().equals(outPoint)) {
            // funding_tx
            logger.debug("checkUnspentChannel(): funding unspent=" + checkUnspentString(channel.getFundingTxUnspent()));
            return channel.getFundingTxUnspent();
        } else {
            // commit_tx
            PtarmiganChannel.CommitTxid commit_tx = channel.getCommitTxid((int)outPoint.getIndex());
            if ((commit_tx != null) && (commit_tx.txid != null)) {
                logger.debug("checkUnspentChannel(): commit_tx unspent=" + checkUnspentString(commit_tx.unspent));
                return commit_tx.unspent;
            }
        }
        return CHECKUNSPENT_FAIL;
    }


    /** 指定したoutPointのunspentチェック
     *      各blockからvinのoutPointを比較し、存在すればSPENT、存在しなければさらに過去blockをたどる。
     *
     *      前回失敗したのであれば最後に成功したblock hashから再開する。
     *      そうでない場合は、現在のblockから開始する。
     *
     *      たどるblock数は、channelがあればfunding_txの現在のblock heightから直近のconfirmation計測時のheight + OFFSET。
     *      funding_tx以外であれば最大でwallet作成時まで遡る
     *
     * @param channel   channel(for limit block height)
     * @param outPoint  outpoint
     * @return  CHECKUNSPENT_xxx
     */
    private int checkUnspentFromBlock(PtarmiganChannel channel, TransactionOutPoint outPoint) {
        logger.debug("checkUnspentFromBlock(): outPoint=" + outPoint.toString());
        if ((channel != null) && Sha256Hash.ZERO_HASH.equals(channel.getMinedBlockHash())) {
            logger.error("checkUnspentFromBlock(): minedHash=ZERO");
            return CHECKUNSPENT_FAIL;
        }
        boolean isFundingTx = (channel != null) && channel.isFundingTx(outPoint);

        Sha256Hash blockHash = null;
        int depth = 0;      //遡る段数(0のとき、段数は考慮しない)
        if (isFundingTx) {
            //exceptionで中断したところから再開(中断していないならnull)
            blockHash = channel.getLastUnspentHash();
        }
        if (blockHash == null) {
            //最新blockから開始
            blockHash = wak.wallet().getLastBlockSeenHash();
        }
        if (blockHash == null) {
            logger.error("checkUnspentFromBlock: FAIL blockHash");
            return CHECKUNSPENT_FAIL;
        }
        int confHeight = 0;
        if ((channel != null) && (channel.getShortChannelId() != null)) {
            logger.debug("height: short_channel_id=" + channel.getShortChannelId().height + ", conf=" + channel.getConfirmation());
            confHeight = channel.getShortChannelId().height + channel.getConfirmation() - 1;
        }
        if (confHeight > 0) {
            depth = wak.wallet().getLastBlockSeenHeight() - confHeight + OFFSET_CHECK_UNSPENT;
        }
        logger.debug("checkUnspentFromBlock(): currentHeight=" + wak.wallet().getLastBlockSeenHeight());
        logger.debug("checkUnspentFromBlock(): block=" + blockHash.toString());
        logger.debug("checkUnspentFromBlock(): depth=" + depth);
        if ((channel != null) && blockHash.equals(channel.getMinedBlockHash())) {
            logger.debug("checkUnspentFromBlock(): minedHash=" + channel.getMinedBlockHash());
        }
        try {
            while (true) {
                Block block = getBlock(blockHash);
                if (block == null) {
                    logger.error("checkUnspentFromBlock: FAIL block");
                    return CHECKUNSPENT_FAIL;
                }
                if (block.getTransactions() == null) {
                    logger.error("checkUnspentFromBlock: FAIL block txs");
                    return CHECKUNSPENT_FAIL;
                }
                String blockName = blockHash.toString();
                saveDownloadLog(STARTUPLOG_BLOCK, "..." + blockName.substring((Sha256Hash.LENGTH - 3) * 2));
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
                            //logger.debug("   input:" + pnt.toString());
                            if (pnt.equals(outPoint)) {
                                logger.debug("checkUnspentFromBlock() ----> SPENT!");
                                return CHECKUNSPENT_SPENT;
                            }
                        }
                    }
                }
                if (isFundingTx) {
                    channel.setLastUnspentHash(blockHash);
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
                depth--;
                if (depth == 0) {
                    //
                    logger.debug("checkUnspentFromBlock() ");
                    break;
                }
                logger.debug("checkUnspentFromBlock() depth=" + depth);

                // ひとつ前のブロック
                blockHash = block.getPrevBlockHash();
            }
        } catch (Exception e) {
            //logger.error("checkUnspentFromBlock(): rethrow: " + getStackTrace(e));
            //throw e;
            logger.error("checkUnspentFromBlock(): FAIL: " + getStackTrace(e));
            return CHECKUNSPENT_FAIL;
        }

        logger.debug("checkUnspentFromBlock(): UNSPENT");
        if (channel != null) {
            channel.setLastUnspentHash(null);
        }
        return CHECKUNSPENT_UNSPENT;
    }

    private String checkUnspentString(int spent) {
        switch (spent) {
            case CHECKUNSPENT_FAIL: return "FAIL";
            case CHECKUNSPENT_UNSPENT: return "UNSPENT";
            case CHECKUNSPENT_SPENT: return "SPENT";
            default: return "unknown";
        }
    }


    /////////////////////////////////////////////////////////////////////////

    /** get receive address(scriptPubKey)
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


    /////////////////////////////////////////////////////////////////////////

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


    /////////////////////////////////////////////////////////////////////////

    /** add channel information
     *
     * @param peerId channel peer node_id
     * @param shortChannelId short_channel_id
     * @param txid funding transaction TXID
     * @param vIndex funding transaction vout
     * @param scriptPubKey witnessScript
     * @param blockHashBytes (lastConfirm=0)establish starting block hash / (lastConfirm>0)mined block hash
     * @param lastConfirm last confirmation(==0:establish starting block hash)
     */
    public boolean setChannel(
            byte[] peerId,
            long shortChannelId,
            byte[] txid, int vIndex,
            byte[] scriptPubKey,
            byte[] blockHashBytes,
            int lastConfirm) {
        boolean result = false;
        logger.debug("setChannel() peerId=" + Hex.toHexString(peerId));
        try {
            TransactionOutPoint fundingOutpoint = new TransactionOutPoint(params, vIndex, Sha256Hash.wrapReversed(txid));
            Sha256Hash blockHash = Sha256Hash.wrapReversed(blockHashBytes);
            //
            PtarmiganChannel channel = mapChannel.get(Hex.toHexString(peerId));
            int prevConfirm;
            if (channel == null) {
                logger.debug("    ADD NEW CHANNEL!!");
                channel = new PtarmiganChannel(peerId, new ShortChannelParam());
                prevConfirm = 0;
            } else {
                logger.debug("    change channel settings: " + channel.getShortChannelId().toString());
                prevConfirm = channel.getConfirmation();
            }
            //
            int minedHeight = 0;
            try {
                BlockStore bs = wak.chain().getBlockStore();
                StoredBlock sb = bs.get(blockHash);
                if (sb != null) {
                    minedHeight = sb.getHeight();
                    logger.debug("setChannel: update minedHeight from BlockStore: " + minedHeight);
                } else {
                    logger.error("setChannel: fail StoredBlock");
                }
            } catch (BlockStoreException e) {
                logger.error("setChannel 1: " + getStackTrace(e));
            }
            if ( (minedHeight == 0) &&
                    (channel.getShortChannelId() != null) &&
                    (channel.getShortChannelId().height > 0) ) {
                minedHeight = channel.getShortChannelId().height;
                logger.debug("setChannel: update minedHeight from short_channel_id: " + minedHeight);
            }
            if (minedHeight == 0) {
                minedHeight = getHeightFromBlock(blockHash);
                logger.debug("setChannel: update minedHeight from blockHeightFromBlock: " + minedHeight);
            }
            int blockHeight = wak.wallet().getLastBlockSeenHeight();

            logger.debug("  shortChannelId=" + shortChannelId);
            logger.debug("  fundingOutpoint=" + fundingOutpoint.toString());
            logger.debug("  scriptPubKey=" + Hex.toHexString(scriptPubKey));
            logger.debug("  prevConfirm=" + prevConfirm);
            logger.debug("  lastConfirm=" + lastConfirm);
            logger.debug("  minedBlockHash=" + blockHash.toString());
            logger.debug("  minedHeight=" + minedHeight);
            logger.debug("  blockCount =" + blockHeight);

            //shortChannelIdが0以外ならheight, bIndex, vIndexが更新される
            //channel.initialize(shortChannelId, fundingOutpoint, (txRaw == null));
            channel.initialize(shortChannelId, fundingOutpoint, CHECKUNSPENT_FAIL);
            channel.setMinedBlockHash(blockHash, minedHeight, -1);

            //check unspent before update confirmation
            channel.setConfirmation(lastConfirm);
            if (!Sha256Hash.ZERO_HASH.equals(blockHash)) {
                int chk_un = checkUnspentFromBlock(channel, fundingOutpoint);
                logger.debug("setChannel: checkUnspent: " + chk_un);
                channel.setFundingTxSpentValue(chk_un);
            } else {
                logger.debug("setChannel: checkUnspent: SKIP");
            }

            if (minedHeight > 0) {
                logger.debug("setChannel: minedConfirm");
                channel.setConfirmation(blockHeight - minedHeight + 1);
            } else {
                logger.debug("setChannel: confirm not set");
            }
            try {
                SegwitAddress address = SegwitAddress.fromHash(params, scriptPubKey);
                wak.wallet().addWatchedAddress(address);
            } catch (Exception e) {
                logger.error("setChannel 2: " + getStackTrace(e));
            }
            logger.debug("setChannel: add channel: " + Hex.toHexString(peerId));

            debugShowRegisteredChannel();
            mapChannel.put(Hex.toHexString(channel.peerNodeId()), channel);
            result = true;
        } catch (Exception e) {
            logger.error("setChannel: " + getStackTrace(e));
        }
        logger.debug("setChannel: exit(" + result + ")");
        return result;
    }


    /////////////////////////////////////////////////////////////////////////

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


    /////////////////////////////////////////////////////////////////////////

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


    /////////////////////////////////////////////////////////////////////////

    /** get balance
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


    /////////////////////////////////////////////////////////////////////////

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
            sendRequest.feePerKb = Coin.valueOf(estimateFee());
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

    /** get Peer
     *
     * @return  peer
     * @throws PtarmException   fail
     */
    private Peer getPeer() throws PtarmException {
        //Peer peer = wak.peerGroup().getDownloadPeer();
        if (connectedPeerIndex >= wak.peerGroup().numConnectedPeers()) {
            connectedPeerIndex = 0;
        }
        Peer peer = wak.peerGroup().getConnectedPeers().get(connectedPeerIndex);
        if (peer != null) {
            logger.debug("getPeer()=" + connectedPeerIndex);
            peerFailCount = 0;
        } else {
            peerFailCount++;
            logger.error("  getPeer(count=" + peerFailCount + ") - peer not found");
            if (peerFailCount > MAX_PEER_FAIL) {
                throw new PtarmException("getPeer: too many fail peer");
            }
        }
        nextPeer();
        return peer;
    }

    private void nextPeer() {
        connectedPeerIndex++;
    }


    /////////////////////////////////////////////////////////////////////////

    /** get Block from cache or peer
     *
     * @param blockHash     block hash
     * @return  block
     * @throws PtarmException   fail
     */
    private Block getBlock(Sha256Hash blockHash) throws PtarmException {
        logger.debug("getBlock():" + blockHash);
        if (Sha256Hash.ZERO_HASH.equals(blockHash)) {
            logger.error("  getBlock() - zero");
            return null;
        }
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
        for (int lp = 0; lp < RETRY_GETBLOCK; lp++) {
            Peer peer = getPeer();
            if (peer == null) {
                logger.error("  getBlockFromPeer() - peer not found");
                return null;
            }
            try {
                block = peer.getBlock(blockHash).get(TIMEOUT_GETBLOCK, TimeUnit.MILLISECONDS);
                if (block != null) {
                    logger.debug("  getBlockFromPeer() " + blockHash.toString());
                    blockCache.put(blockHash, block);
                    downloadFailCount = 0;
                }
                break;
            } catch (Exception e) {
                downloadFailCount++;
                logger.error("getBlockFromPeer(count=" + downloadFailCount + ")");
                if (downloadFailCount >= MAX_DOWNLOAD_FAIL) {
                    throw new PtarmException("getBlockFromPeer: stop SPV: too many fail download");
                }
                if (e instanceof TimeoutException) {
                    logger.error("  getBlockFromPeer(): Timeout==> retry");
                } else {
                    break;
                }
            }
        }
        return block;
    }



    /** blockheight from blockhash
     *
     * @param blockHash target block hash
     * @return  (>0)height, (==0)error
     */
    private int getHeightFromBlock(Sha256Hash blockHash) {
        logger.debug("getHeightFromBlock(): blockHash=" + blockHash.toString());
        long height = 0;
        int depth = 0;

        try {
            while (true) {
                Block block = getBlock(blockHash);
                if (block == null) {
                    logger.error("getHeightFromBlock: fail block");
                    return 0;
                }
                if (block.getTransactions() == null) {
                    logger.error("getHeightFromBlock: fail txs");
                    return 0;
                }
                height = getHeightFromCoinbase(block);
                if (height > 0) {
                    break;
                }

                depth++;
                if (depth > MAX_HEIGHT_FAIL) {
                    logger.error("getHeightFromBlock: fail too many depth");
                    return 0;
                }
                logger.debug("getHeightFromBlock() depth=" + depth);

                // ひとつ前のブロック
                blockHash = block.getPrevBlockHash();
            }
        } catch (Exception e) {
            logger.error("getHeightFromBlock(): exception: " + getStackTrace(e));
        }

        return (int)height + depth;
    }


    private long getHeightFromCoinbase(Block block) {
        long height = 0;
        try {
            List<Transaction> txs = block.getTransactions();
            if ((txs != null) && txs.size() > 0 && (txs.get(0).getInputs().size() > 0)) {
                //logger.debug("COINBASE_toString=" + block.getTransactions().get(0).toString());
                Script scriptSig = txs.get(0).getInput(0).getScriptSig();
                //logger.debug("COINBASE_scriptSig=" + scriptSig.toString());
                ScriptChunk scriptChunk0 = scriptSig.getChunks().get(0);
                //logger.debug("COINBASE_scriptChunk0=" + scriptChunk0.toString());
                //logger.debug("COINBASE_scriptChunk0opcode=" + scriptChunk0.opcode);
                //logger.debug("COINBASE_scriptChunk0Hex=" + Hex.toHexString(scriptChunk0.data));
                if (scriptChunk0.isPushData() && (scriptChunk0.data != null) && (scriptChunk0.data.length == 3)) {
                    height = ((scriptChunk0.data[2] & 0xff) << 16) | ((scriptChunk0.data[1] & 0xff) << 8) | (scriptChunk0.data[0] & 0xff);
                    logger.debug("COINBASE_height=" + height);
                    block.verify((int) height, EnumSet.of(Block.VerifyFlag.HEIGHT_IN_COINBASE));
                }
            }
        } catch (Exception e) {
            logger.error("fail");
        }

        return height;
    }


    /////////////////////////////////////////////////////////////////////////

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
            return peer.getPeerMempoolTransaction(txHash).get(TIMEOUT_GETBLOCK, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.error("getPeerMempoolTransaction(): " + getStackTrace(e));
        }
        return null;
    }


    /////////////////////////////////////////////////////////////////////////

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


    /////////////////////////////////////////////////////////////////////////

    /** Txのspent登録チェック
     *
     * @param channel   channel
     * @param txHash    txid
     * @return  hit commit_txid index or COMMITTXID_MAX(fail)
     */
    private int checkCommitTxids(PtarmiganChannel channel, Sha256Hash txHash) {
        for (int i = COMMITTXID_LOCAL; i < COMMITTXID_MAX; i++) {
            if ((channel.getCommitTxid(i).txid != null) && channel.getCommitTxid(i).txid.equals(txHash)) {
                return i;
            }
        }
        return COMMITTXID_MAX;
    }


    /////////////////////////////////////////////////////////////////////////

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
