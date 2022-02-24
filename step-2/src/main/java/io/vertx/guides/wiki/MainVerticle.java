/*
 *  Copyright (c) 2017 Red Hat, Inc. and/or its affiliates.
 *  Copyright (c) 2017 INSA Lyon, CITI Laboratory.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vertx.guides.wiki;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.guides.wiki.db.DbVerticle;
import io.vertx.guides.wiki.http.HttpVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="https://julien.ponge.org/">Julien Ponge</a>
 */
public class MainVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

  @Override
  public void start(Promise<Void> promise) throws Exception {
    vertx.deployVerticle(DbVerticle.class.getName())
      .compose(result -> {
        return vertx.deployVerticle(HttpVerticle.class.getName());
      })
      .onSuccess(result -> {
        LOGGER.info("db and http verticles have been initialized");
        promise.complete();
      })
      .onFailure(error -> {
        LOGGER.info("FAILED to initialize either the db or http verticle");
        promise.fail(error);
      });
  }

}
