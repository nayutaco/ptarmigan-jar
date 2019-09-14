package co.nayuta.lightning;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

class FeeRate {
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
}
