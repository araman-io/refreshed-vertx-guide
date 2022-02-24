package io.vertx.guides.wiki.database;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

public class DatabaseVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseVerticle.class);
  private JDBCPool dbPool;
  private static final String SQL_CREATE_PAGES_TABLE = "create table if not exists Pages (Id integer identity primary key, Name varchar(255) unique, Content clob)";
  private static final String SQL_GET_PAGE = "select Id, Content from Pages where Name = ?";
  private static final String SQL_CREATE_PAGE = "insert into Pages values (NULL, ?, ?)";
  private static final String SQL_SAVE_PAGE = "update Pages set Content = ? where Id = ?";
  private static final String SQL_ALL_PAGES = "select Name from Pages";
  private static final String SQL_DELETE_PAGE = "delete from Pages where Id = ?";
  private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n" + "\n" + "Feel-free to write in Markdown!\n";


  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    this.dbPool = JDBCPool.pool(vertx, new JsonObject()
      .put("url", "jdbc:hsqldb:file:db/wiki")
      .put("driver_class", "org.hsqldb.jdbcDriver").put("max_pool_size", 30));

    dbPool.query(SQL_CREATE_PAGES_TABLE)
      .execute()
      .onSuccess(result -> {
        LOGGER.info("database has been initialized");
        vertx.eventBus().consumer("wikidb.queue", this::onMessage);  // <3>
        startPromise.complete();
      })
      .onFailure(error -> {
        LOGGER.info("something went wrong with the db step  {}", error);
        startPromise.fail(error);
      });
  }

  private void onMessage(Message<JsonObject> message) {
    if (!message.headers().contains("action")) {
      LOGGER.error("No action header specified for message with headers {} and body {}", message.headers(), message.body().encodePrettily());
      message.fail(ErrorCodes.NO_ACTION_SPECIFIED.ordinal(), "no action specified");
    }
    String action = message.headers().get("action");
    switch (action) {
      case "all-pages":
        this.fetchAllPages(message);
        break;
      case "delete-page":
        this.deletePage(message);
        break;
      case "upsert-page":
        this.upsertPage(message);
        break;
      case "fetch-page":
        this.fetchPage(message);
        break;
    }
  }

  private void upsertPage(Message<JsonObject> message) {
    JsonObject request = message.body();
    Boolean newPage = request.getBoolean("newPage");
    Integer id = request.getInteger("id");
    String pageName = request.getString("title");
    String rawContent = request.getString("markDown");
    Future<RowSet<Row>> query;

    LOGGER.info("retrieved data to upsert {} {} {}", newPage, id, pageName);

    if (newPage) {
      query = this.dbPool.preparedQuery(SQL_CREATE_PAGE)
        .execute(Tuple.of(pageName, rawContent));
    } else {
      query = this.dbPool.preparedQuery(SQL_SAVE_PAGE)
        .execute(Tuple.of(rawContent, id));
    }

    query
      .onSuccess(o -> {
        message.reply(new JsonObject());
      })
      .onFailure(error -> {
        message.fail(ErrorCodes.DB_ERROR.ordinal(), error.getMessage());
      });
  }

  private void fetchPage(Message<JsonObject> message) {
    String pageName = message.body().getString("page");
    this.dbPool.preparedQuery(SQL_GET_PAGE).execute(Tuple.of(pageName))
      .onSuccess(rows -> {
        JsonObject templateData = new JsonObject();
        RowIterator<Row> iterator = rows.iterator();
        if (iterator.hasNext()) {
          Row row = iterator.next();
          templateData.put("id", row.getInteger("ID"));
          templateData.put("rawContent", row.getString("CONTENT"));
          templateData.put("newPage", "no");
        } else {
          templateData.put("id", -1);
          templateData.put("rawContent", EMPTY_PAGE_MARKDOWN);
          templateData.put("newPage", "yes");
        }
        templateData.put("title", pageName);
        templateData.put("timestamp", new Date().toString());

        message.reply(templateData);
      })
      .onFailure(error -> {
        message.fail(ErrorCodes.DB_ERROR.ordinal(), error.getMessage());
      });
  }

  private void deletePage(Message<JsonObject> message) {
    Integer id = message.body().getInteger("id");
    this.dbPool
      .preparedQuery(SQL_DELETE_PAGE)
      .execute(Tuple.of(id))
      .onSuccess(rows -> {
        message.reply(new JsonObject());
      })
      .onFailure(error -> {
        message.fail(ErrorCodes.DB_ERROR.ordinal(), error.getMessage());
      });
  }

  private void fetchAllPages(Message<JsonObject> message) {
    this.dbPool
      .query(SQL_ALL_PAGES)
      .execute()
      .onSuccess(rows -> {
        JsonArray pages = new JsonArray();
        for (Row r : rows) {
          pages.add(r.getString("NAME"));
        }
        JsonObject result = new JsonObject().put("pages", pages);
        LOGGER.info("fetched following pages {} {}", pages.size());
        message.reply(result);
      })
      .onFailure(error -> {
        message.fail(ErrorCodes.DB_ERROR.ordinal(), error.getMessage());
      });
  }

  public enum ErrorCodes {
    NO_ACTION_SPECIFIED,
    BAD_ACTION,
    DB_ERROR
  }

}
