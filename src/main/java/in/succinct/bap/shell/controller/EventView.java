package in.succinct.bap.shell.controller;

import com.venky.core.string.StringUtil;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.path.Path;
import com.venky.swf.routing.Config;
import com.venky.swf.views.BytesView;
import com.venky.swf.views.View;
import org.eclipse.jetty.http.HttpStatus;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class EventView extends View {

    public EventView(Path path){
        super(path);
    }

    public void write(int httpStatusCode){
        HttpServletResponse response = getPath().getResponse();
        response.setStatus(httpStatusCode);
        response.setContentType(MimeType.TEXT_EVENT_STREAM.toString());
        response.addHeader("Cache-Control","no-cache");
        response.addHeader("Connection","keep-alive");
        response.setCharacterEncoding(StandardCharsets.UTF_8.toString());
        try {
            response.flushBuffer();
        }catch (Exception ex){
            //
        }
    }
    public void write(String event) throws IOException{
        HttpServletResponse response = getPath().getResponse();
        response.getWriter().println(String.format("data: %s\n\n", event));
        response.getWriter().flush();
    }

}
