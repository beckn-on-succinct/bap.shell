package in.succinct.bap.shell.controller;

import com.venky.core.security.Crypt;
import com.venky.core.string.StringUtil;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.controller.Controller;
import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.db.model.CryptoKey;
import com.venky.swf.db.model.reflection.ModelReflector;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.AsyncTaskManager;
import com.venky.swf.plugins.background.core.AsyncTaskManagerFactory;
import com.venky.swf.plugins.background.core.DbTask;
import com.venky.swf.plugins.background.core.DbTaskManager;
import com.venky.swf.plugins.background.core.IOTask;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.plugins.background.eventloop.CoreEvent;
import com.venky.swf.plugins.background.eventloop.DbFuture;
import com.venky.swf.plugins.beckn.tasks.BecknApiCall;
import com.venky.swf.routing.Config;
import com.venky.swf.sql.Expression;
import com.venky.swf.sql.Operator;
import com.venky.swf.sql.Select;
import com.venky.swf.views.BytesView;
import com.venky.swf.views.NoContentView;
import com.venky.swf.views.View;
import in.succinct.bap.shell.controller.proxies.BapController;
import in.succinct.bap.shell.controller.proxies.BppController;
import in.succinct.bap.shell.controller.proxies.ResponseSynchronizer;
import in.succinct.beckn.Acknowledgement;
import in.succinct.beckn.Acknowledgement.Status;
import in.succinct.beckn.BecknException;
import in.succinct.beckn.Context;
import in.succinct.beckn.Error;
import in.succinct.beckn.Error.Type;
import in.succinct.beckn.Message;
import in.succinct.beckn.Request;
import in.succinct.beckn.Response;
import in.succinct.beckn.SellerException.GenericBusinessError;
import in.succinct.beckn.SellerException.InvalidRequestError;
import in.succinct.beckn.SellerException.InvalidSignature;
import in.succinct.beckn.Subscriber;
import in.succinct.onet.core.adaptor.NetworkAdaptor;
import in.succinct.onet.core.adaptor.NetworkAdaptorFactory;
import org.eclipse.jetty.http.HttpStatus;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("unused")
public class NetworkController extends Controller implements BapController, BppController {
    public NetworkController(Path path) {
        super(path);
    }
    protected View ack(Request request){
        Response response = new Response(new Acknowledgement(Status.ACK));
        return new BytesView(getPath(),response.getInner().toString().getBytes(StandardCharsets.UTF_8), MimeType.APPLICATION_JSON);
    }

    protected View no_content(){
        return new BytesView(getPath(),new byte[]{},MimeType.APPLICATION_JSON){
            @Override
            public void write() throws IOException {
                super.write(HttpServletResponse.SC_NO_CONTENT);
            }
        };
    }
    protected Response nack(Request request, Throwable th){
        Response response = new Response(new Acknowledgement(Status.NACK));
        if (th != null){
            Error error = new Error();
            response.setError(error);
            if (th.getClass().getName().startsWith("org.openapi4j")){
                InvalidRequestError sellerException = new InvalidRequestError();
                error.setType(Type.JSON_SCHEMA_ERROR);
                error.setCode(sellerException.getErrorCode());
                error.setMessage(sellerException.getMessage());
            }else if (th instanceof BecknException){
                BecknException bex = (BecknException) th;
                error.setType(Type.DOMAIN_ERROR);
                error.setCode(bex.getErrorCode());
                error.setMessage(bex.getMessage());
            }else {
                error.setMessage(th.toString());
                error.setCode(new GenericBusinessError().getErrorCode());
                error.setType(Type.DOMAIN_ERROR);
            }
        }
        return response;
    }
    protected View nack(Request request, Throwable th, String realm){

        Response response = nack(request,th);

        return new BytesView(getPath(),
                response.getInner().toString().getBytes(StandardCharsets.UTF_8),
                MimeType.APPLICATION_JSON,"WWW-Authenticate","Signature realm=\""+realm+"\"",
                "headers=\"(created) (expires) digest\""){
            @Override
            public void write() throws IOException {
                if (th instanceof InvalidSignature){
                    super.write(HttpServletResponse.SC_UNAUTHORIZED);
                }else {
                    super.write(HttpServletResponse.SC_BAD_REQUEST);
                }
            }
        };
    }



    public static NetworkAdaptor getNetworkAdaptor() {
        return NetworkAdaptorFactory.getInstance().getAdaptor(Config.instance().getProperty("in.succinct.onet.name","beckn_open"));
    }
    public static String getCryptoKeyId(String subscriberId){

        String keyId = Config.instance().getProperty("in.succinct.onet.subscriber.key.id");

        if (ObjectUtil.isVoid(keyId)) {
            List<CryptoKey> latest = new Select().from(CryptoKey.class).
                    where(new Expression(ModelReflector.instance(CryptoKey.class).getPool(), "PURPOSE",
                            Operator.EQ, CryptoKey.PURPOSE_SIGNING)).
                    orderBy("ID DESC").execute(1);
            if (latest.isEmpty()) {
                keyId = subscriberId + ".k0";
            } else {
                keyId =latest.get(0).getAlias();
            }
        }
        return keyId;
    }


    private static Subscriber bSubscriber = new Subscriber() {{
        setSubscriberId(Config.instance().getProperty("in.succinct.onet.subscriber.id", Config.instance().getHostName()));
        setUniqueKeyId(getCryptoKeyId(getSubscriberId()));
        setCity(Config.instance().getProperty("in.succinct.onet.subscriber.city",
                Config.instance().getProperty("in.succinct.onet.registry.wild.card.character", "")));
        setType(Subscriber.SUBSCRIBER_TYPE_BAP);
        setCountry(Config.instance().getProperty("in.succinct.onet.country.iso.3", "IND"));
        setDomain(Config.instance().getProperty("in.succinct.onet.subscriber.domain",
                Config.instance().getProperty("in.succinct.onet.registry.wild.card.character", "")));
        setSubscriberUrl(String.format("%s/%s/%s",
                Config.instance().getServerBaseUrl() ,
                getNetworkAdaptor().getId(),
                "/network"));
        setAlias(getUniqueKeyId());
        getNetworkAdaptor().getSubscriptionJson(this);
    }};
    public static Subscriber getSubscriber(){
        return bSubscriber;
    }

    public View act(){
        try {
            String action = getPath().action();
            boolean isSearch = ObjectUtil.equals(action,"search");
            Request request = new Request((JSONObject) Request.parse(StringUtil.read(getPath().getInputStream())));
            initializeRequest(request);
            Subscriber self = getSubscriber();
            String auth = request.generateAuthorizationHeader(self.getSubscriberId(), self.getUniqueKeyId());
            NetworkAdaptor networkAdaptor = getNetworkAdaptor();

            Subscriber transmittedToSubscriber = isSearch ? networkAdaptor.getSearchProvider() :
                    networkAdaptor.lookup(request.getContext().getBppId(),true).get(0);

            final EventView eventView = new EventView(getPath());

            ResponseSynchronizer.getInstance().open(request);

            CoreEvent.spawnOff(false,new CoreEvent() {

                boolean networkBroadcastComplete = false;
                @Override
                public void execute() {
                    super.execute();
                    if (!networkBroadcastComplete) {
                        networkBroadcastComplete = true;
                        ResponseSynchronizer.getInstance().start(request, isSearch? (ObjectUtil.isVoid(request.getContext().getBppId())?Integer.MAX_VALUE:1) : 1);

                        BecknApiCall.build().url(transmittedToSubscriber.getSubscriberUrl(),
                                        request.getContext().getAction()).schema(networkAdaptor.getDomains().get(request.getContext().getDomain()).getSchemaURL()).
                                headers(new HashMap<>() {{
                                    put("Content-Type", "application/json");
                                    put("Accept", "application/json");
                                    put("Authorization", auth);
                                }}).path("/"+action).request(request).call();
                    } else {
                        Request response = null;
                        while ((response = ResponseSynchronizer.getInstance().getNextResponse(request.getContext().getMessageId())) != null) {
                            try {
                                eventView.write(response.toString());
                            } catch (IOException ex) {
                                ResponseSynchronizer.getInstance().close(request.getContext().getMessageId());
                            }
                            //response = ResponseSynchronizer.getInstance().getNextResponse(request.getContext().getMessageId());
                        }
                    }
                }
                @Override
                public boolean isReady() {
                    return super.isReady() && ResponseSynchronizer.getInstance().isComplete(request.getContext().getMessageId());
                }

            });

            return eventView;
        }catch (Exception ex){
            throw new RuntimeException(ex);
        }
    }

    private void initializeRequest(Request request) {
        Subscriber self = getSubscriber();
        NetworkAdaptor networkAdaptor = getNetworkAdaptor();
        Context context = request.getContext();
        if (context == null) {
            context = new Context();
            request.setContext(context);
        }
        context.setBapId(self.getSubscriberId());
        context.setBapUri(self.getSubscriberUrl());
        context.setCity(self.getCity());
        context.setCountry(self.getCountry());
        context.setAction(getPath().action());
        if (context.getTtl() == 0){
            context.setTtl(15L);
        }
        context.setTimestamp(new Date());
        context.setNetworkId(networkAdaptor.getId());
        context.setCoreVersion(networkAdaptor.getCoreVersion());
        context.setDomain(self.getDomain());
        if (ObjectUtil.isVoid(context.getTransactionId())) {
            context.setTransactionId(UUID.randomUUID().toString());
        }
        if (ObjectUtil.isVoid(context.getMessageId())) {
            context.setMessageId(UUID.randomUUID().toString());
        }
    }

    public View on_act(){
        Request request = null;
        try {
            request = new Request((JSONObject) Request.parse(StringUtil.read(getPath().getInputStream())));
            if ( !Config.instance().getBooleanProperty("beckn.auth.enabled", false)  ||
                    request.verifySignature("Authorization",getPath().getHeaders())){
                ResponseSynchronizer.getInstance().addResponse(request);
            }
            return ack(request);
        }catch (Exception ex){
            return nack(request,ex,getSubscriber().getSubscriberId());
        }
    }


    public View subscribe() {
        getNetworkAdaptor().subscribe(getSubscriber());
        return new BytesView(getPath(),"Subscription initiated!".getBytes(StandardCharsets.UTF_8),MimeType.APPLICATION_JSON);
    }


    @RequireLogin(false)
    public View subscriber_json(){
        return new BytesView(getPath(),getSubscriber().toString().getBytes(StandardCharsets.UTF_8),MimeType.APPLICATION_JSON);
    }

    @RequireLogin(false)
    public View on_subscribe() throws Exception{
        String payload = StringUtil.read(getPath().getInputStream());
        JSONObject object = (JSONObject) JSONValue.parse(payload);

        Subscriber registry = getNetworkAdaptor().getRegistry();

        if (registry.getEncrPublicKey() == null){
            throw new RuntimeException("Could not find registry keys for " + registry);
        }



        PrivateKey privateKey = Crypt.getInstance().getPrivateKey(Request.ENCRYPTION_ALGO,
                CryptoKey.find(getSubscriber().getUniqueKeyId(),CryptoKey.PURPOSE_ENCRYPTION).getPrivateKey());

        PublicKey registryPublicKey = Request.getEncryptionPublicKey(registry.getEncrPublicKey());

        KeyAgreement agreement = KeyAgreement.getInstance(Request.ENCRYPTION_ALGO);
        agreement.init(privateKey);
        agreement.doPhase(registryPublicKey,true);

        SecretKey key = agreement.generateSecret("TlsPremasterSecret");

        JSONObject output = new JSONObject();
        output.put("answer", Crypt.getInstance().decrypt((String)object.get("challenge"),"AES",key));

        return new BytesView(getPath(),output.toString().getBytes(),MimeType.APPLICATION_JSON);
    }

}
