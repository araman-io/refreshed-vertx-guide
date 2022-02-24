package io.vertx.guides.wiki.http;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Integer.valueOf;

public class HttpVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpVerticle.class);
  private FreeMarkerTemplateEngine templateEngine;

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    HttpServer server = vertx.createHttpServer();
    templateEngine = FreeMarkerTemplateEngine.create(vertx);

    Router router = Router.router(vertx);   // <2>
    router.get("/").handler(this::indexHandler);
    router.get("/wiki/:page").handler(this::pageRenderingHandler); // <3>
    router.post().handler(BodyHandler.create());  // <4>
    router.post("/save").handler(this::pageUpdateHandler);
    router.post("/create").handler(this::pageCreateHandler);
    router.post("/delete").handler(this::pageDeletionHandler);

    server
      .requestHandler(router)   // <5>
      .listen(8080)
      .onSuccess(result -> {
        LOGGER.info("HTTP server running on port 8080");
        startPromise.complete();
      })
      .onFailure(error -> {
        LOGGER.error("Could not start a HTTP server", error);
        startPromise.fail(error);
      });
  }

  private void pageDeletionHandler(RoutingContext routingContext) {
    Integer id = valueOf(routingContext.request().getParam("id"));
    JsonObject requestPayload = new JsonObject().put("id", id);
    DeliveryOptions options = new DeliveryOptions().addHeader("action", "delete-page");
    LOGGER.info("going to delete page " + id);

    vertx
      .eventBus()
      .request("wikidb.queue", requestPayload, options)
      .onSuccess(message -> {
        routingContext.redirect("/");
      })
      .onFailure(error -> {
        routingContext.fail(error);
      });
  }

  private void pageCreateHandler(RoutingContext routingContext) {
    String pageName = routingContext.request().getParam("name");
    String location;
    if (pageName == null || pageName.isEmpty()) {
      location = "/";
    } else {
      location = "/wiki/" + pageName;
    }
    LOGGER.info("retrieved name " + pageName + " and location " + location);
    routingContext.response().setStatusCode(303).putHeader("Location", location).end();
  }

  private void pageUpdateHandler(RoutingContext routingContext) {
    Integer id = valueOf(routingContext.request().getParam("id"));
    String title = routingContext.request().getParam("title");
    boolean newPage = "yes".equals(routingContext.request().getParam("newPage"));
    String markdown = routingContext.request().getParam("markdown");

    DeliveryOptions options = new DeliveryOptions().addHeader("action", "upsert-page");
    JsonObject requestPayload = new JsonObject()
      .put("id", id)
      .put("title", title)
      .put("newPage", newPage)
      .put("markDown", markdown);

    vertx.eventBus()
      .request("wikidb.queue", requestPayload, options)
      .onSuccess(message -> {
        routingContext.response().setStatusCode(303);
        routingContext.response().putHeader("Location", "/wiki/" + title);
        routingContext.response().end();
      })
      .onFailure(error -> {
        routingContext.fail(error);
      });
  }

  private void pageRenderingHandler(RoutingContext routingContext) {
    String pageName = routingContext.pathParam("page");
    JsonObject requestPayload = new JsonObject().put("page", pageName);
    DeliveryOptions options = new DeliveryOptions().addHeader("action", "fetch-page");
    vertx.eventBus()
      .request("wikidb.queue", requestPayload, options)
      .compose(response -> {
        JsonObject templateData = (JsonObject) response.body();
        templateData.put("content", Processor.process(templateData.getString("rawContent")));
        return templateEngine.render(templateData, "templates/page.ftl");
      })
      .onSuccess(data -> {
        routingContext.response().putHeader("Content-Type", "text/html");
        routingContext.response().end(data.toString());
      })
      .onFailure(error -> {
        routingContext.fail(500, error);
      });
  }

  private void indexHandler(RoutingContext routingContext) {
    DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages");
    vertx.eventBus()
      .request("wikidb.queue", new JsonObject(), options)
      .compose(message -> {
        JsonObject response = (JsonObject) message.body();
        JsonObject templateData = new JsonObject().put("title", "Home of our Wiki!!!");
        templateData.put("pages", response.getJsonArray("pages"));
        LOGGER.info("We found {} pages in the database", templateData.getJsonArray("pages").size());
        return templateEngine.render(templateData, "templates/index.ftl");
      })
      .onSuccess(data -> {
        routingContext.response().putHeader("Content-Type", "text/html");
        routingContext.response().end(data.toString());
      })
      .onFailure(error -> {
        routingContext.fail(500, error);
      });
  }
}
