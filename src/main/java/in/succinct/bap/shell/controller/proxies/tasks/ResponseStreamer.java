package in.succinct.bap.shell.controller.proxies.tasks;

import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.HttpTask;
import com.venky.swf.views.EventView;
import com.venky.swf.views._IView;
import in.succinct.bap.shell.controller.proxies.ResponseSynchronizer.Tracker;
import in.succinct.beckn.Request;

import java.io.IOException;

public class ResponseStreamer extends HttpTask {
    Tracker tracker ;
    EventView eventView ;
    
    @Override
    public boolean isDatabaseAccessed() {
        return false;
    }
    
    public ResponseStreamer(Path path, Tracker tracker){
        super(path);
        this.tracker = tracker;
        this.eventView = new EventView(getPath());
        tracker.registerListener(this);
    }
    
    @Override
    public _IView createView(){
        Request response ;
        try {
            while ((response = tracker.nextResponse()) != null) {
                eventView.write(response.toString(), false);
            }
            if (tracker.isComplete()){
                eventView.write(String.format("{\"done\" : true , \"message_id\" : \"%s\"}\n\n", tracker.getMessageId()),true);
                tracker.close();
            }else {
                tracker.registerListener(this);
            }
            return eventView;
        }catch (IOException ex){
            tracker.close();
            throw new RuntimeException(ex);
        }
    }
}
