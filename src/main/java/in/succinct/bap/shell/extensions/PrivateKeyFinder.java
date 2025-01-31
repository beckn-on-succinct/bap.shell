package in.succinct.bap.shell.extensions;

import com.venky.core.util.ObjectHolder;
import com.venky.extension.Extension;
import com.venky.extension.Registry;
import in.succinct.bap.shell.network.Network;
import in.succinct.beckn.Request;
import in.succinct.beckn.Subscriber;


public class PrivateKeyFinder implements Extension {
    static {
        Registry.instance().registerExtension("private.key.get.Ed25519",new PrivateKeyFinder());
    }

    @Override
    public void invoke(Object... context) {
        ObjectHolder<String> holder = (ObjectHolder<String>) context[0];
        if (holder.get() != null){
            return;
        }
        Subscriber self = Network.getInstance().getSubscriber(Subscriber.SUBSCRIBER_TYPE_BAP,Network.getInstance().getNetworkId()); // This would be incomplete
        String privateKey = Request.getPrivateKey(self.getSubscriberId(),self.getUniqueKeyId());
        holder.set(String.format("%s|%s:%s",self.getSubscriberId() , self.getUniqueKeyId(),privateKey));
    }
}
