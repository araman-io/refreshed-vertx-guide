# Dependencies

The first batch of dependencies to add to the Maven `pom.xml` file are those for the web processing and rendering:
* __vertx-core__
* __vertx-jdbc-client__ Vert.x JDBC client library provides access to any JDBC-compliant database and we will use embedded HSQLDB to keep the setup simple. _note_: Vert.x offers specific JDBC clients for MySQL or PostgreSQL databases. These libraries offers better performance by working with these 2 database server network protocols rather than going through the (blocking) JDBC APIs. 
* __vertx-web__

You can find the complete set of dependencies in the `pom.xml` on `step-1` branch

# MainVerticle initialization phases

To get our wiki running, we need to perform a 2-phases initialization:

1. we need to establish a JDBC database connection, and also make sure that the database schema is in place, and
2. we need to start a HTTP server for the web application.

Each phase can fail (e.g., the HTTP server TCP port is already being used), and they should not run in parallel as the web application code first needs the database access to work.

To make our code _cleaner_ we will define 1 method per phase, and adopt a pattern of returning a _future_ object to notify when each of the phases completes, and whether it did so successfully or not:

```
private Future<Void> prepareDatabase() {
  Promise<Void> promise = Promise.promise();
  // (...)
  return promise.future();
}

private Future<Void> startHttpServer() {
  Promise<Void> promise = Promise.promise();
  // (...)
  return promise.future();
}
```

By having each method returning a _future_ object, the `MainVerticle.start` becomes a composition. When the _future_ of `prepareDatabase` completes successfully, then `startHttpServer` and when both complete successfuly we call `promise.complete` to indicate the verticle has started. If any step fa fails we call `promise.fail`
```
      .onSuccess(result -> {
        LOGGER.info("db and httpserver started");
        promise.complete();
      }).onFailure(error -> {
        LOGGER.error("something went wrong while setting up the db or httpserver {}", error);
        promise.fail(error);
      });
```

# Database initialization

The wiki database schema consists of a single table `Pages` with the following columns:

| Column     | Type       | Description                         |
| ----       | ----       | -----                               |
| Id         | Integer    | Primary Key                         |
| Name       | Characters | Name of a wiki page, must be unique |
| Content    | Text       | markdown text of a wiki page        |

The database operations will be typical _create, read, update, delete_ operations. To get us started, we simply store the corresponding SQL queries as static fields of the `MainVerticle` class. Note that they are written in a SQL dialect that HSQLDB understands, but that other relational databases may not necessarily support. The `?` in the queries are placeholders to pass data when executing queries, and the Vert.x JDBC client prevents from SQL injections.

The verticle needs to keep a reference to a `JDBCPool` object (from the `io.vertx.ext.jdbc` package) that serves as the connection to the database. We do so using a field in `MainVerticle`, and we also create a general-purpose logger from the `org.slf4j` package:

# HTTP server & initialization

The HTTP server makes use of the `vertx-web` project to easily define _dispatching routes_ for incoming HTTP requests. _router_ dispatches requests to different processing handlers depending on the URL, the HTTP method, etc. The initialization consists in setting up a _request router_, then starting the HTTP server:

# Router handlers
The router dispatches calls to handlers to process incoming request and sends a `io.vertx.ext.web.RoutingContext`. RoutingContext is used to send the response and content back to the browser. 


We will follow the implementation of `indexHandler` to list all pages stored in the wiki. The implementation is a straightforward composition. 
1. `select *` SQL query retrieves all the pages in the database
2. Query results are transformed to `JsonObject` 
3. Passed to the FreeMarker engine to render the HTML response.
4. `onSuccess` when both the database and freemarker rendering steps successfuly complete; the response is sent back to the browser
5. `onFailure` handles when either one of the steps fails

```
    this.dbPool
      .query(SQL_ALL_PAGES)   					//1
      .execute()
      .compose(rows -> {                                        //2
        JsonObject templateData = new JsonObject();
	...
	...
        return Future.succeededFuture(templateData);
      })
      .compose(templateData -> {				//3
        return templateEngine.render(templateData, "templates/index.ftl");
      })
      .onSuccess(data -> {					//4
        context.response().putHeader("Content-Type", "text/html");
        context.response().end(data.toString());
      })
      .onFailure(error -> {					//5
        context.fail(error);
      });
```

# Running the application

At this stage we have a working, self-contained wiki application.

To run it we first need to build it with Maven:

```
    $ mvn clean compile exec:java
```

You can then point your favorite web browser to http://localhost:8080/ and enjoy using the wiki.


