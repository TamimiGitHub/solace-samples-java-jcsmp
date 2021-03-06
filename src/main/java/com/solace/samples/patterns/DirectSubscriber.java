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

package com.solace.samples.patterns;

import java.io.IOException;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;

/** This is a more detailed subscriber sample. */
public class DirectSubscriber {

    private static final String SAMPLE_NAME = DirectSubscriber.class.getSimpleName();
    private static final String TOPIC_PREFIX = "solace/samples";  // used as the topic "root"

    private static volatile int msgRecvCounter = 0;              // num messages received
    private static volatile boolean hasDetectedDiscard = false;  // detected any discards yet?
    private static volatile boolean isShutdown = false;          // are we done yet?

    /** the main method. */
    public static void main(String... args) throws JCSMPException, IOException {
        if (args.length < 3) {  // Check command line arguments
            System.out.printf("Usage: %s <host:port> <message-vpn> <client-username> [password]%n%n", SAMPLE_NAME);
            System.exit(-1);
        }
        System.out.println(SAMPLE_NAME + " initializing...");

        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, args[0]);          // host:port
        properties.setProperty(JCSMPProperties.VPN_NAME,  args[1]);     // message-vpn
        properties.setProperty(JCSMPProperties.USERNAME, args[2]);      // client-username
        if (args.length > 3) {
            properties.setProperty(JCSMPProperties.PASSWORD, args[3]);  // client-password
        }
        properties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);  // subscribe Direct subs after reconnect
        JCSMPChannelProperties channelProps = new JCSMPChannelProperties();
        channelProps.setReconnectRetries(20);      // recommended settings
        channelProps.setConnectRetriesPerHost(5);  // recommended settings
        // https://docs.solace.com/Solace-PubSub-Messaging-APIs/API-Developer-Guide/Configuring-Connection-T.htm
        properties.setProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES, channelProps);
        final JCSMPSession session;
        session = JCSMPFactory.onlyInstance().createSession(properties, null, new SessionEventHandler() {
            @Override
            public void handleEvent(SessionEventArgs event) {  // could be reconnecting, connection lost, etc.
                System.out.printf("### Received a Session event: %s%n", event);
            }
        });
        session.connect();  // connect to the broker

        // Anonymous inner-class for MessageListener, this demonstrates the async threaded message callback
        final XMLMessageConsumer consumer = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                // do not print anything to console... too slow!
                msgRecvCounter++;
                if (message.getDiscardIndication()) {  // since Direct messages, check if there have been any lost any messages
                    // If the consumer is being over-driven (i.e. publish rates too high), the broker might discard some messages for this consumer
                    // check this flag to know if that's happened
                    // to avoid discards:
                    //  a) reduce publish rate
                    //  b) use multiple-threads or shared subscriptions for parallel processing
                    //  c) increase size of consumer's D-1 egress buffers (check client-profile) (helps more with bursts)
                    hasDetectedDiscard = true;  // set my own flag
                }
                if (message.getDestination().getName().endsWith("control/quit")) {  // special sample message
                    System.out.println("QUIT message received, shutting down.");  // exampe of command-and-control w/msgs
                    isShutdown = true;
                }
            }

            @Override
            public void onException(JCSMPException e) {  // uh oh!
                System.out.printf("### MessageListener's onException(): %s%n",e);
                if (e instanceof JCSMPTransportException) {  // unrecoverable, all reconnect attempts failed
                    isShutdown = true;  // let's quit
                }
            }
        });

        session.addSubscription(JCSMPFactory.onlyInstance().createTopic(TOPIC_PREFIX + "/direct/>"));
        session.addSubscription(JCSMPFactory.onlyInstance().createTopic(TOPIC_PREFIX + "/control/>"));
        consumer.start();
        System.out.println(SAMPLE_NAME + " connected, and running. Press [ENTER] to quit.");
        try {
            while (System.in.available() == 0 && !isShutdown) {
                Thread.sleep(1000);  // wait 1 second
                System.out.printf("Received msgs/s: %,d%n",msgRecvCounter);  // simple way of calculating message rates
                msgRecvCounter = 0;
                if (hasDetectedDiscard) {
                    System.out.println("*** Egress discard detected *** : "
                            + SAMPLE_NAME + " unable to keep up with full message rate");
                    hasDetectedDiscard = false;  // only show the error once per second
                }
            }
        } catch (InterruptedException e) {
            // Thread.sleep() interrupted... probably getting shut down
        }
        isShutdown = true;
        session.closeSession();  // will also close consumer object
        System.out.println("Main thread quitting.");
    }
}
