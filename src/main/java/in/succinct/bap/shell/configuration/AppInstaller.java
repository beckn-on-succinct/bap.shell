package in.succinct.bap.shell.configuration;

import com.venky.swf.configuration.Installer;
import com.venky.swf.plugins.background.core.DbTask;
import com.venky.swf.plugins.background.core.TaskManager;
import in.succinct.bap.shell.controller.NetworkController;

public class AppInstaller implements Installer{

  public void install() {
    /*
    TaskManager.instance().executeAsync((DbTask)()->{
      NetworkController.getNetworkAdaptor().subscribe(NetworkController.getSubscriber());
    });

     */
  }
}

