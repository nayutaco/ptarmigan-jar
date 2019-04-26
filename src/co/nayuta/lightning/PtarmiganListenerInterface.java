package co.nayuta.lightning;

import org.bitcoinj.core.Transaction;

import java.util.EventListener;

public interface PtarmiganListenerInterface extends EventListener {
    void confFundingTransactionHandler(PtarmiganChannel channel, Transaction tx);
    void sendFundingTransactionHandler(PtarmiganChannel channel, Transaction tx);

    void confTransactionHandler(PtarmiganChannel channel, Transaction tx);
    void sendTransactionHandler(PtarmiganChannel channel, Transaction tx);
}