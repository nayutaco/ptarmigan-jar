import co.nayuta.lightning.Ptarmigan;
import co.nayuta.lightning.SearchOutPointResult;
import co.nayuta.lightning.ShortChannelParam;

import java.util.ArrayList;
import java.util.List;

public class Main
{
    public static void main( String[] argv ) {
        Ptarmigan ptarm = new Ptarmigan();

        int val;
        val = Ptarmigan.CHECKUNSPENT_FAIL;
        val = Ptarmigan.CHECKUNSPENT_UNSPENT;
        val = Ptarmigan.CHECKUNSPENT_SPENT;
        val = Ptarmigan.COMMITTXID_LOCAL;
        val = Ptarmigan.COMMITTXID_REMOTE;
        val = Ptarmigan.COMMITTXID_MAX;
        val = Ptarmigan.SPV_START_OK;
        val = Ptarmigan.SPV_START_FILE;
        val = Ptarmigan.SPV_START_BJ;
        val = Ptarmigan.SPV_START_ERR;

        try {
            int dummyInt = 0;
            byte[] dummyBytes = null;
            ShortChannelParam dummyChan = null;
            SearchOutPointResult dummySearch = null;
            List<byte[]> listDummy = null;
            boolean dummyBool = false;
            long dummyLong = 0;
            dummyInt = ptarm.spv_start("test");
            ptarm.setCreationHash(dummyBytes);
            dummyInt = ptarm.getBlockCount(dummyBytes);
            dummyBytes = ptarm.getGenesisBlockHash();
            dummyInt = ptarm.getTxConfirmation(dummyBytes, -1, null, 0);
            dummyChan = ptarm.getShortChannelParam(dummyBytes);
//            dummyBytes = ptarm.getTxidFromShortChannelId(0);
            dummySearch = ptarm.searchOutPoint(0, dummyBytes, 0);
            listDummy = ptarm.searchVout(0, new ArrayList<byte[]>());
            dummyBytes = ptarm.signRawTx(0, dummyBytes);
            dummyBytes = ptarm.sendRawTx(dummyBytes);
            dummyBool = ptarm.checkBroadcast(dummyBytes, dummyBytes);
            dummyInt = ptarm.checkUnspent(dummyBytes, dummyBytes, 0);
            String dummyAddr = ptarm.getNewAddress();
            dummyLong = ptarm.estimateFee();
            dummyBool = ptarm.setChannel(dummyBytes, 0, dummyBytes, 0, dummyBytes, dummyBytes, 0);
            ptarm.delChannel(dummyBytes);
            //ptarm.setCommitTxid(dummyBytes, 0, 0, null);
            dummyLong = ptarm.getBalance();
            dummyBytes = ptarm.emptyWallet("");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
