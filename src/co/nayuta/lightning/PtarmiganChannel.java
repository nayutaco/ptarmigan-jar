package co.nayuta.lightning;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionOutPoint;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PtarmiganChannel {
    //
    class CommitTxid {
        int commitNum = 0;
        Sha256Hash txid = null;
        Boolean unspent = true;
    }
    //
    private byte[] peerNodeId;
    private Ptarmigan.ShortChannelParam shortChannelId;
    private boolean fundingTxUnspent = true;
    private int confirmation = -1;
    private CommitTxid[] commitTxids = new CommitTxid[Ptarmigan.COMMITTXID_MAX];
    private TransactionOutPoint fundingOutpoint;
    private Sha256Hash minedHash = Sha256Hash.ZERO_HASH;
    private Logger logger;
    //
    PtarmiganChannel(byte[] peerNodeId, Ptarmigan.ShortChannelParam shortChannelId) {
        logger = LoggerFactory.getLogger(this.getClass());

        logger.debug("PtarmiganChannel ctor");
        logger.debug("  peerNodeId:" + Hex.toHexString(peerNodeId));
        logger.debug("  shortChannelId: " + ((shortChannelId != null) ? shortChannelId: "null"));
        this.peerNodeId = peerNodeId;
        this.shortChannelId = shortChannelId;
        for (int i = 0; i < commitTxids.length; i++) {
            commitTxids[i] = new CommitTxid();
        }
    }
    //
    void initialize(long shortChannelId,
                    TransactionOutPoint fundingOutpoint,
                    boolean fundingTxUnspent) {
        if (shortChannelId != 0) {
            this.shortChannelId.initialize(shortChannelId);
        }
        if (fundingOutpoint != null) {
            this.fundingOutpoint = fundingOutpoint;
            this.fundingTxUnspent = fundingTxUnspent;
            this.shortChannelId.vIndex = (int)fundingOutpoint.getIndex();
        }

        logger.debug("initialized(node=" + Hex.toHexString(this.peerNodeId) + "):");
        logger.debug("  shortChannelId=" + this.shortChannelId);
        logger.debug("  fundingOutpoint=" + ((fundingOutpoint != null) ? fundingOutpoint.toString() : "null"));
        logger.debug("  confirmation=" + this.confirmation);
    }
    //
    Ptarmigan.ShortChannelParam getShortChannelId() {
        if ( (this.confirmation > 0) && this.shortChannelId.isAvailable() ) {
            return this.shortChannelId;
        } else {
            return null;
        }
    }
    //
    byte[] peerNodeId() {
        return this.peerNodeId;
    }
    //
    TransactionOutPoint getFundingOutpoint() {
        return this.fundingOutpoint;
    }
    boolean getFundingTxUnspent() {
        return this.fundingTxUnspent;
    }
    //
    void setFundingTxSpent() {
        logger.debug("setFundingTxSpent(node=" + Hex.toHexString(this.peerNodeId) + ")");
        this.fundingTxUnspent = false;
    }
    //
    void setConfirmation(int conf) {
        if (conf != 0) {
            this.confirmation = conf;
            logger.debug("setConfirmation=" + this.confirmation + "(node=" + Hex.toHexString(this.peerNodeId) + ")");
        } else {
            throw new NullPointerException();
        }
    }

    int getConfirmation() {
        return this.confirmation;
    }
    //
    void setMinedBlockHash(Sha256Hash hash, int height, int bIndex) {
        if ((hash != null) && !hash.equals(Sha256Hash.ZERO_HASH)) {
            this.minedHash = hash;
            logger.debug("  minedHash update");
        }
        if (height > 0) {
            this.shortChannelId.height = height;
            logger.debug("  height update");
        }
        if (bIndex != -1) {
            this.shortChannelId.bIndex = bIndex;
            logger.debug("  bindex update");
        }
        logger.debug("setMinedBlockHash(node=" + Hex.toHexString(this.peerNodeId) + "):");
        logger.debug("  minedHash=" + this.minedHash.toString());
        logger.debug("  height=" + this.shortChannelId.height);
        logger.debug("  bindex=" + this.shortChannelId.bIndex);
    }
    //
    Sha256Hash getMinedBlockHash() {
        return this.minedHash;
    }
    //
    void setCommitTxid(int index, int commitNum, Sha256Hash txid) {
        commitTxids[index].commitNum = commitNum;
        commitTxids[index].txid = txid;
        logger.debug("setCommitTxid[" + commitNum + "]=" + txid.toString() + "(node=" + Hex.toHexString(this.peerNodeId) + ")");
    }

    CommitTxid getCommitTxid(int index) {
        return commitTxids[index];
    }
    //
    @Override
    public String toString() {
        return this.shortChannelId + ", minedHash=" + ((this.minedHash != null) ? this.minedHash.toString() : "null");
    }
}
