package in.succinct.bap.shell.controller;

import com.venky.core.string.StringUtil;
import com.venky.core.util.Bucket;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.controller.Controller;
import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.db.Database;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.AsyncTaskManagerFactory;
import com.venky.swf.plugins.background.core.Task;
import com.venky.swf.plugins.background.eventloop.CoreEvent;
import com.venky.swf.plugins.beckn.tasks.BecknApiCall;
import com.venky.swf.routing.Config;
import com.venky.swf.sql.Expression;
import com.venky.swf.sql.Operator;
import com.venky.swf.sql.Select;
import com.venky.swf.views.BytesView;
import com.venky.swf.views.EventView;
import com.venky.swf.views.NoContentView;
import com.venky.swf.views.View;
import in.succinct.bap.shell.controller.proxies.BapController;
import in.succinct.bap.shell.controller.proxies.BppController;
import in.succinct.bap.shell.controller.proxies.ResponseSynchronizer;
import in.succinct.bap.shell.controller.proxies.ResponseSynchronizer.Tracker;
import in.succinct.bap.shell.db.model.BecknAction;
import in.succinct.bap.shell.network.Network;
import in.succinct.beckn.Acknowledgement;
import in.succinct.beckn.Acknowledgement.Status;
import in.succinct.beckn.BecknException;
import in.succinct.beckn.BecknObjects;
import in.succinct.beckn.Context;
import in.succinct.beckn.Error;
import in.succinct.beckn.Error.Type;
import in.succinct.beckn.Request;
import in.succinct.beckn.Response;
import in.succinct.beckn.SellerException.GenericBusinessError;
import in.succinct.beckn.SellerException.InvalidRequestError;
import in.succinct.beckn.SellerException.InvalidSignature;
import in.succinct.beckn.Subscriber;
import in.succinct.onet.core.adaptor.NetworkAdaptor;
import in.succinct.onet.core.adaptor.NetworkAdaptorFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

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



    public NetworkAdaptor getNetworkAdaptor() {
        return NetworkAdaptorFactory.getInstance().getAdaptor(Network.getInstance().getNetworkId());
    }
    public String getCryptoKeyId(String subscriberId){
        return Network.getInstance().getCryptoKeyId(subscriberId);
    }

    public Subscriber getSubscriber(){
        Subscriber subscriber = Network.getInstance().getSubscriber(Subscriber.SUBSCRIBER_TYPE_BAP, getNetworkAdaptor().getId());
        subscriber.setUniqueKeyId(getCryptoKeyId(subscriber.getSubscriberId()));
        subscriber.setAlias(subscriber.getUniqueKeyId());
        //getNetworkAdaptor().getSubscriptionJson(subscriber);
        return subscriber;
    }

    public View read_events(String messageId){
        final EventView eventView = new EventView(getPath());
        Tracker tracker = ResponseSynchronizer.getInstance().getTracker(messageId,false);

        if (tracker != null) {
            CoreEvent.spawnOff(false, new CoreEvent() {

                @Override
                public void execute() {
                    super.execute();
                    Request response = null;
                    synchronized (tracker) {
                        Bucket numResponsesReceived = new Bucket();
                        while ((response = tracker.nextResponse()) != null) {
                            try {
                                numResponsesReceived.increment();
                                eventView.write(response.toString());
                            } catch (IOException ex) {
                                ResponseSynchronizer.getInstance().closeTracker(messageId);
                            }
                        }
                        try {
                            if (tracker.isComplete()) {
                                ResponseSynchronizer.getInstance().closeTracker(messageId);
                                eventView.write(String.format("{\"done\" : true , \"message_id\" : \"%s\"}",messageId));
                            } else if (numResponsesReceived.intValue() == 0){
                                tracker.registerListener(this);
                            }
                        }catch (Exception ex){
                            ResponseSynchronizer.getInstance().closeTracker(messageId);
                        }
                    }
                }

                @Override
                public boolean isReady() {
                    return super.isReady() && ( !tracker.isBeingObserved() || tracker.isComplete()); //Clients will auto reconnect.
                }
            });
        }else {
            try {
                writePersistedResponses(eventView,messageId);
                eventView.write("{\"done\" : true}");
            }catch (Exception ex){
                Config.instance().getLogger(getClass().getName()).log(Level.WARNING,"Inactive message could not be sent" ,ex);
            }
        }
        return eventView;
    }

    private void writePersistedResponses(EventView eventView, String messageId) {
        Select select = new Select().from(BecknAction.class);
        select.where(new Expression(select.getPool(),"MESSAGE_ID", Operator.EQ,messageId));
        List<BecknAction> actionList = select.execute();
        actionList.forEach(a->{
            try {
                eventView.write(a.getResponse());
            }catch (Exception ex){
                return;
            }
        });
    }

    public View act(){
        try {
            String action = getPath().action();
            boolean isSearch = ObjectUtil.equals(action,"search");
            Request request = new Request((JSONObject) Request.parse(StringUtil.read(getPath().getInputStream())));
            Subscriber self = getSubscriber();
            initializeRequest(self,request);
            NetworkAdaptor networkAdaptor = getNetworkAdaptor();

            Subscriber transmittedToSubscriber = isSearch ? networkAdaptor.getSearchProvider() :
                    networkAdaptor.lookup(new Subscriber(){{
                        setSubscriberId(request.getContext().getBppId());
                        setType(Subscriber.SUBSCRIBER_TYPE_BPP);
                    }},true).get(0);

            Tracker tracker = ResponseSynchronizer.getInstance().createTracker(request);

            tracker.start(request, isSearch? (ObjectUtil.isVoid(request.getContext().getBppId())?
                    networkAdaptor.lookup(new Subscriber(){{
                      setType(Subscriber.SUBSCRIBER_TYPE_BPP);
                      setDomain(request.getContext().getDomain());
                      setCity(request.getContext().getCity());
                      setCountry(request.getContext().getCountry());
                    }},true).size():1) : 1,
                    getPath().getHeader("SearchTransactionId"));


            BppRequestTask requestTask = new BppRequestTask(self,transmittedToSubscriber,networkAdaptor,request);
            AsyncTaskManagerFactory.getInstance().addAll(Collections.singleton(requestTask));

            boolean callBackToBeSynchronized = Database.getJdbcTypeHelper("").getTypeRef(boolean.class).getTypeConverter().valueOf(getPath().getHeader("X-CallBackToBeSynchronized"));
            if (!callBackToBeSynchronized) {
                return new BytesView(getPath(),request.getInner().toString().getBytes(StandardCharsets.UTF_8),MimeType.APPLICATION_JSON);
            }else {

                CoreEvent.spawnOff(new CoreEvent(){
                    {
                        tracker.registerListener(this);
                    }
                    @Override
                    public void execute() {
                        super.execute();
                        Requests requests = new Requests();
                        Request response = null;
                        synchronized (tracker) {
                            while ((response = tracker.nextResponse()) != null) {
                                requests.add(response);
                            }
                            if (tracker.isComplete()) {
                                ResponseSynchronizer.getInstance().closeTracker(request.getContext().getMessageId());
                                try {
                                    //Request connection is committed after this response is committed in HttpCoreEvent
                                    new BytesView(getPath(), requests.getInner().toString().getBytes(StandardCharsets.UTF_8), MimeType.APPLICATION_JSON).write();
                                }catch (IOException ex){
                                    throw new RuntimeException(ex);
                                }
                            }else {
                                tracker.registerListener(this);
                            }
                        }
                    }

                    @Override
                    public boolean isReady() {
                        return super.isReady() &&  tracker.isComplete() ;
                    }
                });
                return new NoContentView(getPath()); //Request is kept open
            }



        }catch (Exception ex){
            throw new RuntimeException(ex);
        }
    }

    public static class Requests extends BecknObjects<Request> {
        public Requests() {
        }

        public Requests(JSONArray value) {
            super(value);
        }

        public Requests(String payload) {
            super(payload);
        }
    }
    public static class BppRequestTask implements Task {
        Subscriber to ;
        Subscriber from;
        NetworkAdaptor networkAdaptor;
        Request request;
        Response response = null;
        public BppRequestTask(Subscriber from, Subscriber to, NetworkAdaptor networkAdaptor , Request request){
            this.from = from;
            this.to = to;
            this.networkAdaptor = networkAdaptor;
            this.request = networkAdaptor.getObjectCreator(request.getContext().getDomain()).create(Request.class);
            this.request.update(request);
        }


        @Override
        public void execute() {
            String auth = request.generateAuthorizationHeader(from.getSubscriberId(), from.getUniqueKeyId());

            BecknApiCall call = BecknApiCall.build().url(to.getSubscriberUrl(),
                            request.getContext().getAction()).schema(networkAdaptor.getDomains().get(request.getContext().getDomain()).getSchemaURL()).
                    headers(new HashMap<>() {{
                        put("Content-Type", "application/json");
                        put("Accept", "application/json");
                        put("Authorization", auth);
                    }}).path("/" + request.getContext().getAction()).request(request).call();

            this.response = call.getResponse();
        }

        public Response getResponse() {
            return response;
        }
    }

    private void initializeRequest(Subscriber self,Request request) {
        NetworkAdaptor networkAdaptor = getNetworkAdaptor();
        Context context = request.getContext();
        if (context == null) {
            context = new Context();
            request.setContext(context);
        }
        context.setBapId(self.getSubscriberId());
        context.setBapUri(self.getSubscriberUrl());
        if (context.getCity() == null) {
            context.setCity(self.getCity());
        }
        if (context.getCountry() == null) {
            context.setCountry(self.getCountry());
        }
        context.setAction(getPath().action());
        if (context.get("ttl") == null){
            context.setTtl(10L);
        }
        context.setTimestamp(new Date());
        context.setNetworkId(networkAdaptor.getId());
        context.setCoreVersion(networkAdaptor.getCoreVersion());
        if (ObjectUtil.isVoid(context.getDomain())) {
            context.setDomain(self.getDomain());
        }
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
            request.setObjectCreator(getNetworkAdaptor().getObjectCreator(request.getContext().getDomain()));
            if ( !Config.instance().getBooleanProperty("beckn.auth.enabled", false)  ||
                    request.verifySignature("Authorization",getPath().getHeaders())){

                Request r = new Request();
                r.update(request);
                AsyncTaskManagerFactory.getInstance().addAll(Collections.singleton((Task)()->{
                    ResponseSynchronizer.getInstance().addResponse(r);
                }));
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
        if (registry == null){
            throw new RuntimeException("Cannot verify Signature, Could not find registry keys for " + getNetworkAdaptor().getId());
        }

        if (!Request.verifySignature(getPath().getHeader("Signature"), payload, registry.getSigningPublicKey())){
            throw new RuntimeException("Cannot verify Signature");
        }

        JSONObject output = new JSONObject();
        output.put("answer", Network.getInstance().solveChallenge(getNetworkAdaptor(),getSubscriber(),(String)object.get("challenge")));
        return new BytesView(getPath(),output.toString().getBytes(),MimeType.APPLICATION_JSON);
    }

}
