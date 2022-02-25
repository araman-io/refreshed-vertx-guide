# List of Steps
* Step 2 : breaking the application into multiple verticles
* Step 1 : creating a minimum viable wiki application on a single Verticle

# Step 2

## Breaking application into multiple verticles
In this step we have decomposed the `MainVerticle` into 2 verticles (`HttpVerticle` and `DatabaseVerticle`). Both verticles extend from the AbstractVerticle and @Override the `start(Promise)` method. They mark the promise `complete` or `fail` based on the outcome of the initialization steps. The MainVerticle orchestrates the initialization of the verticles and finally marks its promise `complete` or `fail`.  

We deploy new verticles using the `vertx.deployVerticle` method from the `MainVerticle`

```
    vertx.deployVerticle(DatabaseVerticle.class.getName())
      .compose(result -> {
        return vertx.deployVerticle(HttpVerticle.class.getName());
      })

```

Since verticles communicate over event bus, they register addresses they will consume messages from. In our setup `HttpVerticle` needs to invoke `DatabaseVerticle` and it 
* registers its intent to consume messages from the `wikidb.queue` and 
* a reference to handler which will be inovoked when the messages are received

```
    dbPool.query(SQL_CREATE_PAGES_TABLE)
      .execute()
      .onSuccess(result -> {
        LOGGER.info("database has been initialized");
        vertx.eventBus().consumer("wikidb.queue", this::onMessage);  				// <1>
        startPromise.complete();
      })
      .onFailure(error -> {
        LOGGER.info("something went wrong with the db step  {}", error);
        startPromise.fail(error);
      });
```

## Sending message from one verticle to another
This is where we spend most of the time. Verticles communicate with messages over an `event bus`. I personally think that this is where we have the biggest difference between reactive system and frameworks which support reactive constructs (e.g. spring boot or project reactor where components communicate via references). Vert.x supports several communication paradigms but we will use the request / response paradigm. 

Consider the steps involved in rendering a wiki page.  `HttpVerticle.pageRenderingHandler`. After the verticle receives the reuqest it:
1. Fetches the name of the page from the path parameter. This needs to be sent to the DatabaseVerticle to retrieve the page from the database
2. The request parameters for the DatabaseVerticle is encapsulated as a JsonObject 
3. The intended action to be triggered is encapsulated in DeliveryOptions
4. Access the eventBud and 
5. sends the request to an address with the requestPayload and deliveryOptions
The rest of the steps are similar to Step - 1

```
    String pageName = routingContext.pathParam("page");						//1
    JsonObject requestPayload = new JsonObject().put("page", pageName);				//2
    DeliveryOptions options = new DeliveryOptions().addHeader("action", "fetch-page");		//3
    vertx.eventBus()										//4
      .request("wikidb.queue", requestPayload, options)						//5
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
```

## Receiving message

`DatabaseVerticle` receives and processes messages in 2 stages


__Stage 1__
1. When messages arrive the `onMethod` is invoked. 
2. The DeliveryOptions is retrieved from the message header
3. Using a switch/case statement on `action`
4. the appropriate handler routine is invoked with the incoming message

```
  private void onMessage(Message<JsonObject> message) {			//1
    String action = message.headers().get("action");			//2
    switch (action) {							//3
      ...
      ...
      case "fetch-page":						//4
        this.fetchPage(message);
        break;
    }
  }
```

__Stage 2__
The `fetchPage` method is similar to database retrieval routines with some minor differences

1. retrieves the request parameter from the body of the Message
2. executes the query as normal
3. assembles the response as a JsonObject
4. finally `reply` to the message and passes the assembled response
5. If database retrieval failed then `message.fail` is invoked with the appropriate error message

```
  private void fetchPage(Message<JsonObject> message) {
    String pageName = message.body().getString("page");				//1
    this.dbPool.preparedQuery(SQL_GET_PAGE).execute(Tuple.of(pageName))		//2
      .onSuccess(rows -> {							//3
        JsonObject templateData = new JsonObject();
        RowIterator<Row> iterator = rows.iterator();
        if (iterator.hasNext()) {
          templateData.put("id", row.getInteger("ID"));
	  ...
	  ...
        }
        templateData.put("title", pageName);
        templateData.put("timestamp", new Date().toString());

        message.reply(templateData);						//4
      })
      .onFailure(error -> {
        message.fail(ErrorCodes.DB_ERROR.ordinal(), error.getMessage());	//5
      });
    
```

## Key takeaways
* Communication between verticles only happens through event bus. 
* This is the key differentiator from other frameworks / libraries
* Since Vert.x needs to know how to serialize and deserialize the messages to eventbus we need to use types for which codecs have been registered. JsonObject is the most popular choice. We can pass other datatypes to event bus by registering codecs
* Coming from a invoke by reference world this step looks a little odd and takes some time to get used to. We will uncover an alternate path to mitigate the tedious nature of this development


----

# Step 1
## Dependencies

The first batch of dependencies to add to the Maven `pom.xml` file are those for the web processing and rendering:
* __vertx-core__
* __vertx-jdbc-client__ Vert.x JDBC client library provides access to any JDBC-compliant database and we will use embedded HSQLDB to keep the setup simple. _note_: Vert.x offers specific JDBC clients for MySQL or PostgreSQL databases. These libraries offers better performance by working with these 2 database server network protocols rather than going through the (blocking) JDBC APIs. 
* __vertx-web__

You can find the complete set of dependencies in the `pom.xml` on `step-1` branch

## MainVerticle initialization phases

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

## Database initialization

The wiki database schema consists of a single table `Pages` with the following columns:

| Column     | Type       | Description                         |
| ----       | ----       | -----                               |
| Id         | Integer    | Primary Key                         |
| Name       | Characters | Name of a wiki page, must be unique |
| Content    | Text       | markdown text of a wiki page        |

The database operations will be typical _create, read, update, delete_ operations. To get us started, we simply store the corresponding SQL queries as static fields of the `MainVerticle` class. Note that they are written in a SQL dialect that HSQLDB understands, but that other relational databases may not necessarily support. The `?` in the queries are placeholders to pass data when executing queries, and the Vert.x JDBC client prevents from SQL injections.

The verticle needs to keep a reference to a `JDBCPool` object (from the `io.vertx.ext.jdbc` package) that serves as the connection to the database. We do so using a field in `MainVerticle`, and we also create a general-purpose logger from the `org.slf4j` package:

## HTTP server & initialization

The HTTP server makes use of the `vertx-web` project to easily define _dispatching routes_ for incoming HTTP requests. _router_ dispatches requests to different processing handlers depending on the URL, the HTTP method, etc. The initialization consists in setting up a _request router_, then starting the HTTP server:

## Router handlers
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


