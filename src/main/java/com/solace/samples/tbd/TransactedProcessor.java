/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.solace.samples.tbd;

import java.io.IOException;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;

public class TransactedProcessor {

    public void run(String... args) throws JCSMPException {
        System.out.println("DirectProcessor initializing...");
        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, args[0]);     // host:port
        properties.setProperty(JCSMPProperties.VPN_NAME,  args[1]); // message-vpn
        properties.setProperty(JCSMPProperties.USERNAME, args[2]); // client-username
        if (args.length > 3) {
            properties.setProperty(JCSMPProperties.PASSWORD, args[3]); // client-password
        }
        final JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        final Topic topic = JCSMPFactory.onlyInstance().createTopic("GET/>");
        final Topic topic2 = JCSMPFactory.onlyInstance().createTopic("POST/>");
        

        /** Anonymous inner-class for handling publishing events */
        final XMLMessageProducer producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object key) {
                System.out.println("Producer received response for msg: " + key);
            }

            @Override
            public void handleErrorEx(Object key, JCSMPException e, long timestamp) {
                System.out.printf("Producer received error for msg: %s@%s - %s%n", key, timestamp, e);
            }
        });

        /** Anonymous inner-class for request handling **/
        final XMLMessageConsumer cons = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage request) {

                if (request.getReplyTo() == null) {  // not expecting reply
                    System.out.println("Received message, generating processed");
                    TextMessage reply = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);

                    final String text = request.dump().toUpperCase();
                    try {
                        reply.setText("Your path was: "+request.getProperties().getString("JMS_Solace_HTTP_target_path_query_verbatim"));
                    } catch (Exception e) {
                        reply.setText(text);
                    }
                    System.out.println(request.dump());  // prints the request message to the console
                    reply.setApplicationMessageId(request.getApplicationMessageId());  // needed for correlation
                    System.out.println(reply.dump());
                    try {
                        producer.sendReply(request, reply);
                    } catch (JCSMPException e) {
                        System.out.println("Error sending reply.");
                        e.printStackTrace();
                    }
                } else {
                    System.out.println("Received message without reply-to field");
                }

            }

            public void onException(JCSMPException e) {
                System.out.printf("Consumer received exception: %s%n", e);
            }
        });

        session.addSubscription(topic);
        session.addSubscription(topic2);
        cons.start();

        // Consume-only session is now hooked up and running!
        System.out.println("Listening for request messages on topic " + topic + " ... Press enter to exit");
        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Close consumer
        cons.close();
        System.out.println("Exiting.");
        session.closeSession();

    }

    public static void main2b(String... args) throws JCSMPException {

        // Check command line arguments
        if (args.length < 3) {
            System.out.println("Usage: DirectProcessor <host:port> <message-vpn> <client-username> [client-password]");
            System.out.println();
            System.exit(-1);
        }

        TransactedProcessor processor = new TransactedProcessor();
        processor.run(args);
    }
}