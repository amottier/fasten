/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.fasten.analyzer.javacgopal;

import eu.fasten.analyzer.javacgopal.data.MavenCoordinate;
import eu.fasten.analyzer.javacgopal.data.PartialCallGraph;
import eu.fasten.analyzer.javacgopal.data.exceptions.EmptyCallGraphException;
import eu.fasten.analyzer.javacgopal.data.exceptions.MissingArtifactException;
import eu.fasten.analyzer.javacgopal.data.exceptions.OPALException;
import eu.fasten.core.data.Constants;
import eu.fasten.core.data.ExtendedRevisionJavaCallGraph;
import eu.fasten.core.plugins.KafkaPlugin;
import java.io.File;
import java.util.*;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.json.JSONObject;
import org.pf4j.Extension;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OPALPlugin extends Plugin {

    public OPALPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Extension
    public static class OPAL implements KafkaPlugin {

        private final Logger logger = LoggerFactory.getLogger(getClass());

        private String consumeTopic = "fasten.maven.pkg";
        private Throwable pluginError;
        private ExtendedRevisionJavaCallGraph graph;
        private String outputPath;

        @Override
        public Optional<List<String>> consumeTopic() {
            return Optional.of(new ArrayList<>(Collections.singletonList(consumeTopic)));
        }

        @Override
        public void consume(String kafkaRecord) {
            pluginError = null;
            outputPath = null;
            graph = null;

            var kafkaConsumedJson = new JSONObject(kafkaRecord);
            if (kafkaConsumedJson.has("payload")) {
                kafkaConsumedJson = kafkaConsumedJson.getJSONObject("payload");
            }
            final var mavenCoordinate = new MavenCoordinate(kafkaConsumedJson);

            long startTime = System.nanoTime();
            try {
                // Generate CG and measure construction duration.
                logger.info("[CG-GENERATION] [UNPROCESSED] [-1i] [" + mavenCoordinate.getCoordinate() + "] [NONE] ");
                this.graph = PartialCallGraph.createExtendedRevisionJavaCallGraph(mavenCoordinate,
                        "", "CHA", kafkaConsumedJson.optLong("date", -1));
                long endTime = System.nanoTime();
                long duration = (endTime - startTime) / 1000000; // Compute duration in ms. 

                if (this.graph.isCallGraphEmpty()) {
                    throw new EmptyCallGraphException();
                }

                var groupId = graph.product.split(Constants.mvnCoordinateSeparator)[0];
                var artifactId = graph.product.split(Constants.mvnCoordinateSeparator)[1];
                var version = graph.version;
                var product = artifactId + "_" + groupId + "_" + version;

                var firstLetter = artifactId.substring(0, 1);

                outputPath = File.separator + Constants.mvnForge + File.separator
                        + firstLetter + File.separator
                        + artifactId + File.separator + product + ".json";

                logger.info("[CG-GENERATION] [SUCCESS] [" + duration + "i] [" + mavenCoordinate.getCoordinate() + "] [NONE] ");

            } catch (OPALException | EmptyCallGraphException e) {
                long endTime = System.nanoTime();
                long duration = (endTime - startTime) / 1000000; // Compute duration in ms.

                logger.error("[CG-GENERATION] [FAILED] [" + duration + "i] [" + mavenCoordinate.getCoordinate() + "] [" + e.getClass().getSimpleName() + "] " + e.getMessage(), e);
                setPluginError(e);
            } catch (MissingArtifactException e) {
                long endTime = System.nanoTime();
                long duration = (endTime - startTime) / 1000000; // Compute duration in ms.

                logger.error("[ARTIFACT-DOWNLOAD] [FAILED] [" + duration + "i] [" + mavenCoordinate.getCoordinate() + "] [" + e.getClass().getSimpleName() + "] " + e.getMessage(), e);
                setPluginError(e);
            }
        }

        @Override
        public Optional<String> produce() {
            if (this.graph != null && !this.graph.isCallGraphEmpty()) {
                return Optional.of(graph.toJSON().toString());
            } else {
                return Optional.empty();
            }
        }

        @Override
        public String getOutputPath() {
            return outputPath;
        }

        @Override
        public void setTopic(String topicName) {
            this.consumeTopic = topicName;
        }

        @Override
        public String name() {
            return this.getClass().getCanonicalName();
        }

        @Override
        public String description() {
            return "Generates call graphs for Java packages";
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public Throwable getPluginError() {
            return this.pluginError;
        }

        public void setPluginError(Throwable throwable) {
            this.pluginError = throwable;
        }

        @Override
        public void freeResource() {
        }

        @Override
        public String version() {
            return "0.1.2";
        }

        @Override
        public Properties getConsumerProperties() {
            Properties properties = new Properties();

            // Assign a static ID to the consumer based pods' unique name in K8s env.
            if (System.getenv("POD_INSTANCE_ID") != null) {
                logger.info(String.format("Pod ID: %s", System.getenv("POD_INSTANCE_ID")));
                properties.setProperty(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, System.getenv("POD_INSTANCE_ID"));
            }

            // Proper configuration for OPAL consumption.
            properties.setProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "10000");
            properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "300000");
            properties.setProperty(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "3600000");
            return properties;
        }
    }
}
