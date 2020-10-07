package net.deepcloud.deepx.jobhistory;

import static org.apache.hadoop.yarn.util.StringHelper.join;

import net.deepcloud.deepx.webapp.AMParams;
import net.deepcloud.deepx.webapp.NavBlock;
import org.apache.hadoop.yarn.webapp.WebApp;
import org.apache.hadoop.yarn.webapp.WebApps;
import org.apache.hadoop.yarn.webapp.SubView;
import org.apache.hadoop.yarn.webapp.view.TwoColumnLayout;

public class HsJobPage extends TwoColumnLayout implements AMParams {

  @Override
  protected void preHead(Page.HTML<_> html) {
    super.preHead(html);
    String jobID = $(APP_ID);
    set(TITLE, jobID.isEmpty() ? "Bad request: missing job ID"
        : join($(APP_TYPE) + " Application ", $(APP_ID)));
  }

  @Override
  protected Class<? extends SubView> nav() {
    return NavBlock.class;
  }

  @Override
  protected Class<? extends SubView> content() {
    if ($(APP_TYPE).equals("Tensorflow") || $(APP_TYPE).equals("Mxnet") || $(APP_TYPE).equals("Lightlda") || $(APP_TYPE).equals("Xflow")) {
      return HsJobBlock.class;
    }else{
      return HsSingleJobBlock.class;
    }
  }

  @Override
  protected  Class<? extends SubView> header() {
    try {
      if (WebApps.Builder.class.getMethod("build", WebApp.class) != null) {
        return HeaderBlock.class;
      }
    } catch (NoSuchMethodException e) {
      LOG.debug("current hadoop version don't have the method build of Class " + WebApps.class.toString() + ". For More Detail: " + e);
      return org.apache.hadoop.yarn.webapp.view.HeaderBlock.class;
    }
    return null;
  }

}
