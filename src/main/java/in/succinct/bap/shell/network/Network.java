package in.succinct.bap.shell.network;

import com.venky.core.security.Crypt;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.db.model.CryptoKey;
import com.venky.swf.db.model.reflection.ModelReflector;
import com.venky.swf.routing.Config;
import com.venky.swf.sql.Conjunction;
import com.venky.swf.sql.Expression;
import com.venky.swf.sql.Operator;
import com.venky.swf.sql.Select;
import in.succinct.beckn.Request;
import in.succinct.beckn.Subscriber;
import in.succinct.onet.core.adaptor.NetworkAdaptor;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;

public class Network {
    private static volatile Network sSoleInstance;

    //private constructor.
    private Network() {
        //Prevent form the reflection api.
        if (sSoleInstance != null) {
            throw new RuntimeException("Use getInstance() method to get the single instance of this class.");
        }
    }

    public static Network getInstance() {
        if (sSoleInstance == null) { //if there is no instance available... create new one
            synchronized (Network.class) {
                if (sSoleInstance == null) sSoleInstance = new Network();
            }
        }

        return sSoleInstance;
    }

    //Make singleton from serialize and deserialize operation.
    protected Network readResolve() {
        return getInstance();
    }

    public String getNetworkId() {
        return Config.instance().getProperty("in.succinct.onet.name","beckn_open");
    }

    public String getCryptoKeyId(String subscriberId){

        String keyId = Config.instance().getProperty("in.succinct.onet.subscriber.key.id");

        if (ObjectUtil.isVoid(keyId)) {
            Select select = new Select().from(CryptoKey.class);
            Expression where = new Expression(select.getPool(), Conjunction.AND);

            where.add(new Expression(ModelReflector.instance(CryptoKey.class).getPool(), "PURPOSE",
                    Operator.EQ, CryptoKey.PURPOSE_SIGNING));
            where.add(new Expression(select.getPool(),"ALIAS", Operator.LK,String.format("%s.k%%",subscriberId)));

            List<CryptoKey> latest = select.where(where).orderBy("ID DESC").execute(1);
            if (latest.isEmpty()) {
                keyId = subscriberId + ".k0";
            } else {
                keyId =latest.get(0).getAlias();
            }
        }
        return keyId;
    }
    public Subscriber getSubscriber(String role, String networkId) {
        String relativeUrl = ObjectUtil.equals(role,Subscriber.SUBSCRIBER_TYPE_BAP) ? "network" :
                ObjectUtil.equals(role,Subscriber.SUBSCRIBER_TYPE_BG)? "bg" :
                        ObjectUtil.equals(role,Subscriber.SUBSCRIBER_TYPE_LOCAL_REGISTRY)? "subscribers":
                                ObjectUtil.equals(role,Subscriber.SUBSCRIBER_TYPE_BPP)? "bpp" :null;

        if (ObjectUtil.isVoid(relativeUrl)){
            throw new RuntimeException("Cant figure relative url for " + role );
        }
        return new Subscriber() {
            {
                setSubscriberId(Config.instance().getProperty("in.succinct.onet.subscriber.id", Config.instance().getHostName()));
                setCity(Config.instance().getProperty("in.succinct.onet.subscriber.city",
                        Config.instance().getProperty("in.succinct.onet.registry.wild.card.character", "")));
                setCountry(Config.instance().getProperty("in.succinct.onet.country.iso.3", "IND"));
                setDomain(Config.instance().getProperty("in.succinct.onet.subscriber.domain",
                        Config.instance().getProperty("in.succinct.onet.registry.wild.card.character", "")));
                setUniqueKeyId(getCryptoKeyId(getSubscriberId()));
                setAlias(getUniqueKeyId());
                setType(role);
                setSubscriberUrl(String.format("%s/%s/%s",
                        Config.instance().getServerBaseUrl(),
                        networkId,
                        relativeUrl));
            }
        };
    }

    public String solveChallenge(NetworkAdaptor networkAdaptor, Subscriber participant, String challenge) throws NoSuchAlgorithmException, InvalidKeyException {
        return solveChallenge(networkAdaptor.getRegistry(),participant,challenge);
    }
    public String solveChallenge(Subscriber registry, Subscriber participant, String challenge) throws NoSuchAlgorithmException, InvalidKeyException {
        if (registry == null || registry.getEncrPublicKey() == null){
            throw new RuntimeException("Could not find registry keys for " + registry);
        }



        PrivateKey privateKey = Crypt.getInstance().getPrivateKey(Request.ENCRYPTION_ALGO,
                CryptoKey.find(participant.getUniqueKeyId(),CryptoKey.PURPOSE_ENCRYPTION).getPrivateKey());

        PublicKey registryPublicKey = Request.getEncryptionPublicKey(registry.getEncrPublicKey());

        KeyAgreement agreement = KeyAgreement.getInstance(Request.ENCRYPTION_ALGO);
        agreement.init(privateKey);
        agreement.doPhase(registryPublicKey,true);

        SecretKey key = agreement.generateSecret("TlsPremasterSecret");
        return Crypt.getInstance().decrypt(challenge,"AES",key);
    }
}
