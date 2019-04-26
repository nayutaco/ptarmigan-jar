import co.nayuta.lightning.Ptarmigan;
import org.bitcoinj.core.Sha256Hash;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class Main
{
    public static void main( String[] argv ) {
        Ptarmigan ptarm = null;
        ptarm = new Ptarmigan("");
        ptarm.spv_start();

        int val;
        val = Ptarmigan.CHECKUNSPENT_FAIL;
        val = Ptarmigan.CHECKUNSPENT_UNSPENT;
        val = Ptarmigan.CHECKUNSPENT_SPENT;
        val = Ptarmigan.COMMITTXID_LOCAL;
        val = Ptarmigan.COMMITTXID_REMOTE;
        val = Ptarmigan.COMMITTXID_MAX;

        int dummyInt = 0;
        byte[] dummyBytes = null;
        Ptarmigan.ShortChannelParam dummyChan = null;
        Sha256Hash dummyHash = null;
        List<byte[]> listDummy = null;
        boolean dummyBool = false;
        long dummyLong = 0;
        ptarm.setCreationHash(dummyBytes);
        dummyInt = ptarm.getBlockCount(dummyBytes);
        dummyBytes = ptarm.getGenesisBlockHash();
        dummyInt = ptarm.getTxConfirmation(dummyBytes);
        dummyChan = ptarm.getShortChannelParam(dummyBytes);
        dummyHash = ptarm.getTxidFromShortChannelId(0);
        dummyBytes = ptarm.searchOutPoint(0, dummyBytes, 0);
        listDummy = ptarm.searchVout(0, new ArrayList<byte[]>());
        dummyBytes = ptarm.signRawTx(0, dummyBytes);
        dummyBytes = ptarm.sendRawTx(dummyBytes);
        dummyBool = ptarm.checkBroadcast(dummyBytes);
        dummyInt = ptarm.checkUnspent(dummyBytes, dummyBytes, 0);
        String dummyAddr = ptarm.getNewAddress();
        dummyLong = ptarm.estimateFee();
        ptarm.setChannel(dummyBytes, 0, dummyBytes, 0, dummyBytes, dummyBytes, 0);
        ptarm.delChannel(dummyBytes);
        ptarm.setCommitTxid(dummyBytes, 0, 0, null);
        dummyLong = ptarm.getBalance();
        dummyBytes = ptarm.emptyWallet("");
    }
}
