package in.succinct.bap.shell.controller.proxies;

import com.venky.swf.views.View;

@SuppressWarnings("unused")
public interface BppController {
     View act();

     default View search(){ return act() ; }
     default View select(){ return act() ; }
     default View init(){ return act() ; }
     default View confirm(){ return act() ; }
     default View track(){ return act() ; }
     default View update(){ return act() ; }
     default View status(){ return act() ; }
     default View cancel(){ return act() ; }
     default View rating(){ return act() ; }
     default View support(){ return act() ; }
     default View get_cancellation_reasons(){ return act() ; }
     default View get_return_reasons(){ return act() ; }
     default View get_rating_categories(){ return act() ; }
     default View get_feedback_categories(){ return act() ; }
     default View issue() { return act(); }
     default View issue_status() { return act(); }
     default View receiver_recon() { return act(); }


}
