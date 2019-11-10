package co.nayuta.lightning;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionOutPoint;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

public class PtarmiganChannel {
    //
    static class CommitTxid {
        int commitNum = 0;
        Sha256Hash txid = null;
        int unspent = Ptarmigan.CHECKUNSPENT_FAIL;
    }
    //
    private byte[] peerNodeId;
    private ShortChannelParam shortChannelId;
    private int fundingTxUnspent = Ptarmigan.CHECKUNSPENT_FAIL;
    private Sha256Hash lastUnspentHash = null;
    private int confirmation = -1;
    private CommitTxid[] commitTxids = new CommitTxid[Ptarmigan.COMMITTXID_MAX];
    private TransactionOutPoint fundingOutpoint;
    private Sha256Hash minedHash = Sha256Hash.ZERO_HASH;
    private Sha256Hash spentHash = null;
    private Logger logger;
    //
    PtarmiganChannel(byte[] peerNodeId, ShortChannelParam shortChannelId) {
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
                    int fundingTxUnspent) {
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
    ShortChannelParam getShortChannelId() {
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
    //
    int getFundingTxUnspent() {
        return this.fundingTxUnspent;
    }
    //
    boolean isFundingTx(TransactionOutPoint outPont) {
        return (this.fundingOutpoint != null) && this.fundingOutpoint.equals(outPont);
    }
    //
    void setFundingTxSpentValue(int checkUnspent, Sha256Hash spentHash) {
        logger.debug("setFundingTxSpent(node=" + Hex.toHexString(this.peerNodeId) + ")=" + checkUnspent);
        this.fundingTxUnspent = checkUnspent;
        if (spentHash != null) {
            logger.debug("  spentHash=" + spentHash.toString());
            this.spentHash = spentHash;
        }
    }
    Sha256Hash getFundingTxSpentBlockHash() {
        return this.spentHash;
    }
    //
    Sha256Hash getLastUnspentHash() {
        return this.lastUnspentHash;
    }
    //
    void setLastUnspentHash(@Nullable Sha256Hash failHash) {
        logger.debug("setLastUnspentHash(node=" + Hex.toHexString(this.peerNodeId) + ")=" + ((failHash != null) ? failHash.toString() : "null"));
        this.lastUnspentHash = failHash;
    }
    //
    void setConfirmation(int conf) {
        this.confirmation = conf;
        logger.debug("setConfirmation=" + this.confirmation + "(node=" + Hex.toHexString(this.peerNodeId) + ")");
    }
    int getConfirmation() {
        return this.confirmation;
    }
    //
    void setMinedBlockHash(Sha256Hash hash, int height, int bIndex) {
        if ((hash != null) && !hash.equals(Sha256Hash.ZERO_HASH)) {
            logger.debug("setMinedBlockHash():  minedHash update: before=" + this.minedHash.toString());
            this.minedHash = hash;
        }
        if (height > 0) {
            logger.debug("setMinedBlockHash():  height update: before=" + this.shortChannelId.height);
            this.shortChannelId.height = height;
        }
        if ((this.shortChannelId.bIndex <= 0) && (bIndex >= 0)) {
            logger.debug("setMinedBlockHash():  bindex update: before=" + this.shortChannelId.bIndex);
            this.shortChannelId.bIndex = bIndex;
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
