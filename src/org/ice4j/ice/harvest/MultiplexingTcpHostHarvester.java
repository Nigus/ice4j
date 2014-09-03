/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.ice.*;
import org.ice4j.message.*;
import org.ice4j.socket.*;

import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.logging.*;

/**
 * A <tt>CandidateHarvester</tt> implementation, which listens on a specified
 * list of TCP server sockets. On {@link #harvest(org.ice4j.ice.Component)}, a
 * TCP candidate with type "passive" is added for each of the server sockets.
 *
 * This instance runs two threads: {@link #acceptThread} and
 * {@link #readThread}. The 'accept' thread just accepts new <tt>Socket</tt>s
 * and passes them over to the 'read' thread. The 'read' thread reads a STUN
 * message from an accepted socket and, based on the STUN username, passes it
 * to the appropriate <tt>Component</tt>.
 *
 * @author Boris Grozev
 */
public class MultiplexingTcpHostHarvester
    extends CandidateHarvester
{
    /**
     * Our class logger.
     */
    private static final Logger logger
            = Logger.getLogger(MultiplexingTcpHostHarvester.class.getName());

    /**
     * Channels which we have failed to read from after at least
     * <tt>READ_TIMEOUT</tt> milliseconds will be considered failed and will
     * be closed.
     */
    private static int READ_TIMEOUT = 10000;

    /**
     * The list of <tt>ServerSocketChannel</tt>s that we will <tt>accept</tt> on.
     */
    private final List<ServerSocketChannel> serverSocketChannels
            = new LinkedList<ServerSocketChannel>();

    /**
     * The list of transport addresses which we have found to be listening on,
     * and which we will advertise as candidates in
     * {@link #harvest(org.ice4j.ice.Component)}
     */
    private final List<TransportAddress> localAddresses
            = new LinkedList<TransportAddress>();

    /**
     * The thread which <tt>accept</tt>s TCP connections from the sockets in
     * {@link #serverSocketChannels}.
     */
    private AcceptThread acceptThread;

    /**
     * The thread which reads from the already <tt>accept</tt>ed sockets.
     */
    private ReadThread readThread;

    /**
     * Triggers the termination of the threads of this instance.
     */
    private boolean close = false;

    /**
     * Channels pending to be added to the list that {@link #readThread} reads
     * from.
     */
    private final List<SocketChannel> newChannels
            = new LinkedList<SocketChannel>();

    /**
     * Maps a local "ufrag" to the single <tt>Component</tt> instance with that
     * "ufrag".
     *
     * We only keep weak references, because we do not want to prevent
     * <tt>Component</tt>s from being freed.
     */
    private final Map<String, WeakReference<Component>> components
            = new HashMap<String, WeakReference<Component>>();

    /**
     * The <tt>Pipe</tt> on which {@link #acceptThread} notifies
     * {@link #readThread} about the existence of new accepted channels.
     */
    private Pipe pipe;

    /**
     * Initializes a new <tt>MultiplexingTcpHostHarvester</tt>, which is to
     * listen on port number <tt>port</tt> on all IP addresses on all
     * available interfaces.
     *
     * @param port the port to listen on.
     */
    public MultiplexingTcpHostHarvester(int port)
        throws IOException
    {
        this(port, Collections.list(NetworkInterface.getNetworkInterfaces()));
    }

    /**
     * Initializes a new <tt>MultiplexingTcpHostHarvester</tt>, which is to
     * listen on the specified list of <tt>TransportAddress</tt>es.
     *
     * @param transportAddresses the transport addresses to listen on.
     */
    public MultiplexingTcpHostHarvester(
            List<TransportAddress> transportAddresses)
        throws IOException
    {
        this.localAddresses.addAll(transportAddresses);
        init();
    }

    /**
     * Initializes a new <tt>MultiplexingTcpHostHarvester</tt>, which is to
     * listen on port number <tt>port</tt> on all the IP addresses on the
     * specified <tt>NetworkInterface</tt>s.
     *
     * @param port the port to listen on.
     * @param interfaces the interfaces to listen on.
     */
    public MultiplexingTcpHostHarvester(int port,
                                        List<NetworkInterface> interfaces)
        throws IOException
    {
        initializeLocalAddresses(port, interfaces);
        init();
    }

    /**
     * Initializes {@link #serverSocketChannels}, creates and starts the threads
     * used by this instance.
     */
    private void init()
            throws IOException
    {
        for (TransportAddress transportAddress : localAddresses)
        {
            ServerSocketChannel channel = ServerSocketChannel.open();
            ServerSocket socket = channel.socket();
            socket.bind(
                    new InetSocketAddress(transportAddress.getAddress(),
                                          transportAddress.getPort()));
            serverSocketChannels.add(channel);
        }

        pipe = Pipe.open();

        acceptThread = new AcceptThread();
        acceptThread.start();

        readThread = new ReadThread();
        readThread.start();
    }

    /**
     * Initializes {@link #localAddresses}.
     *
     * @param port the port to use.
     * @param interfaces the list of interfaces to use.
     */
    private void initializeLocalAddresses(
            int port,
            List<NetworkInterface> interfaces)
        throws IOException

    {
        boolean useIPv6 = !StackProperties.getBoolean(
                StackProperties.DISABLE_IPv6,
                false);

        for (NetworkInterface iface : interfaces)
        {
            if (NetworkUtils.isInterfaceLoopback(iface)
                    || !NetworkUtils.isInterfaceUp(iface)
                    || !HostCandidateHarvester.isInterfaceAllowed(iface))
            {
                //this one is obviously not going to do
                continue;
            }

            Enumeration<InetAddress> addresses = iface.getInetAddresses();

            while(addresses.hasMoreElements())
            {
                InetAddress addr = addresses.nextElement();

                if (addr.isLoopbackAddress())
                {
                    //loopback again
                    continue;
                }

                if((addr instanceof Inet4Address) || useIPv6)
                {
                     TransportAddress transportAddress
                        = new TransportAddress(addr, port, Transport.TCP);
                    localAddresses.add(transportAddress);
                }
            }
        }
    }

    /**
     * Returns the <tt>Component</tt> instance, if any, for a given local
     * "ufrag".
     * @param localUfrag the local "ufrag"
     * @return the <tt>Component</tt> instance, if any, for a given local
     * "ufrag".
     */
    private Component getComponent(String localUfrag)
    {
        synchronized (components)
        {
            WeakReference<Component> wr = components.get(localUfrag);

            return wr == null ? null : wr.get();
        }
    }

    /**
     * {@inheritDoc}
     *
     * Saves a (weak) reference to <tt>Component</tt>, so that it can be
     * notified if/when a socket for one of it <tt>LocalCandidate</tt>s is
     * accepted.
     */
    @Override
    public Collection<LocalCandidate> harvest(Component component)
    {
        IceMediaStream stream = component.getParentStream();
        Agent agent = stream.getParentAgent();
        if (stream.getComponentCount() != 1 || agent.getStreamCount() != 1)
        {
            /*
             * MultiplexingTcpHostHarvester only works with streams with a
             * single component, and agents with a single stream. This is
             * because we use the local "ufrag" to de-multiplex the accept()-ed
             * sockets between the known components.
             */
            throw new IllegalStateException("More than one Component for an "
                                            + "Agent, cannot harvest.");
        }

        List<LocalCandidate> candidates = createLocalCandidates(component);
        for (LocalCandidate candidate : candidates)
            component.addLocalCandidate(candidate);


        synchronized (components)
        {
            components.put(agent.getLocalUfrag(),
                           new WeakReference<Component>(component));
        }

        return candidates;
    }

    /**
     * Creates and returns the list of <tt>LocalCandidate</tt>s which are to be added
     * by this <tt>MultiplexingTcpHostHarvester</tt>to a specific
     * <tt>Component</tt>.
     *
     * @param component the <tt>Component</tt> for which to create candidates.
     * @return the list of <tt>LocalCandidate</tt>s which are to be added
     * by this <tt>MultiplexingTcpHostHarvester</tt>to a specific
     * <tt>Component</tt>.
     */
    private List<LocalCandidate> createLocalCandidates(Component component)
    {
        List<LocalCandidate> candidates = new LinkedList<LocalCandidate>();
        for (TransportAddress transportAddress : localAddresses)
        {
            TcpHostCandidate candidate
                    = new TcpHostCandidate(transportAddress, component);
            candidate.setTcpType(CandidateTcpType.PASSIVE);

            candidates.add(new TcpHostCandidate(transportAddress, component));
        }
        return candidates;
    }

    /**
     * A <tt>Thread</tt> which will accept new <tt>SocketChannel</tt>s from all
     * <tt>ServerSocketChannel</tt>s in {@link #serverSocketChannels}.
     */
    private class AcceptThread
        extends Thread
    {
        /**
         * The <tt>Selector</tt> used to select a specific
         * <tt>ServerSocketChannel</tt> which is ready to <tt>accept</tt>.
         */
        private final Selector selector;

        /**
         * The channel to which this <tt>AcceptThread</tt> will write when a
         * new socket has been <tt>accept</tt>ed in order to notify
         * {@link #readThread}.
         */
        private final Pipe.SinkChannel pipeSink;

        /**
         * A <tt>ByteBuffer</tt> used to write to {@link #pipeSink}.
         */
        private final ByteBuffer buffer;

        /**
         * Initializes a new <tt>AcceptThread</tt>.
         */
        private AcceptThread()
            throws IOException
        {
            setName("MultiplexingTcpHostHarvester AcceptThread");

            pipeSink = pipe.sink();
            buffer = ByteBuffer.allocate(1);

            selector = Selector.open();
            for (ServerSocketChannel channel : serverSocketChannels)
            {
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_ACCEPT);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run()
        {
            int readyChannels;
            SocketChannel channel;
            IOException exception;
            boolean added;

            while (true)
            {
                if (close)
                {
                    break;
                }

                try
                {
                    // Allow to go on, so we can quit if closed
                    readyChannels = selector.select(3000);
                }
                catch (IOException ioe)
                {
                    logger.info("Failed to select an accept-ready socket: "
                                    + ioe);
                    break;
                }

                if (readyChannels > 0)
                {
                    synchronized (newChannels)
                    {
                        exception = null;
                        added = false;

                        for (SelectionKey key : selector.selectedKeys())
                        {
                            if (key.isAcceptable())
                            {
                                try
                                {
                                    channel = ((ServerSocketChannel)
                                        key.channel()).accept();
                                }
                                catch (IOException ioe)
                                {
                                    exception = ioe;
                                    break;
                                }

                                // Add the accepted socket to newChannels, so
                                // the 'read' thread can pick it up.
                                newChannels.add(channel);
                                added = true;
                            }
                        }
                        selector.selectedKeys().clear();

                        if (added)
                            notifyReadThread();

                        if (exception != null)
                        {
                            logger.info("Failed to accept a socket, which"
                                            + "should have been ready to accept: "
                                            + exception);
                            break;
                        }
                    }
                }
            } // while(true)

            //now clean up and exit
            for (ServerSocketChannel serverSocketChannel : serverSocketChannels)
            {
                try
                {
                    serverSocketChannel.close();
                }
                catch (IOException ioe)
                {
                }
            }

            try
            {
                selector.close();
            }
            catch (IOException ioe)
            {}

        }

        /**
         * Notifies {@link #readThread} that new channels have been added to
         * {@link #newChannels} by writing to {@link #pipe}.
         */
        private void notifyReadThread()
        {
            try
            {
                //XXX do we have to fill it each time?
                buffer.clear();
                buffer.put((byte) 0);
                buffer.flip();
                pipeSink.write(buffer);
                buffer.flip();
            }
            catch (IOException ioe)
            {
                logger.info("Failed to write to pipe.");
            }
        }
    }

    private class ReadThread
        extends Thread
    {
        /**
         * Contains the <tt>SocketChanel</tt>s that we are currently reading
         * from, mapped to the time they were initially added.
         */
        private final Map<SocketChannel, Long> channels
                = new HashMap<SocketChannel, Long>();

        /**
         * <tt>Selector</tt> used to detect when one of {@link #channels} is
         * ready to be read from.
         */
        private final Selector selector;

        /**
         * The channel on which we will be notified when new channels are
         * available in {@link #newChannels}.
         */
        private final Pipe.SourceChannel pipeSource;

        /**
         * A buffer into which we will read away from {@link #pipeSource}.
         */
        private final ByteBuffer buffer;

        /**
         * Used in {@link #cleanup()}, defined here to avoid allocating on
         * every invocation.
         */
        private final List<SocketChannel> toRemove
                = new LinkedList<SocketChannel>();

        /**
         * A <tt>DatagramPacket</tt> into which we will try to read STUN
         * messages.
         */
        private final DatagramPacket datagramPacket;

        /**
         * Initializes a new <tt>ReadThread</tt>.
         * @throws IOException if the selector to be used fails to open.
         */
        private ReadThread()
                throws IOException
        {
            selector = Selector.open();
            pipeSource = pipe.source();
            pipeSource.configureBlocking(false);
            pipeSource.register(selector, SelectionKey.OP_READ);
            buffer = ByteBuffer.allocate(1);
            datagramPacket = new DatagramPacket(new byte[1500], 1500);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run()
        {
            Set<SelectionKey> selectedKeys;
            int readyChannels = 0;
            SelectableChannel selectedChannel;

            while (true)
            {
                synchronized (MultiplexingTcpHostHarvester.this)
                {
                    if (close)
                        break;
                }

                // clean up stale channels
                cleanup();

                checkForNewChannels();

                try
                {
                    readyChannels = selector.select(READ_TIMEOUT / 2);
                }
                catch (IOException ioe)
                {
                    logger.info("Failed to select a read-ready channel.");
                }

                if (readyChannels > 0)
                {
                    selectedKeys = selector.selectedKeys();
                    for (SelectionKey key : selectedKeys)
                    {
                        if (key.isReadable())
                        {
                            selectedChannel = key.channel();
                            if (selectedChannel == pipeSource)
                            {
                                // That's just the nudge that new channels are
                                // available. Just read from the pipe, so it
                                // doesn't get hogged.
                                try
                                {
                                    pipeSource.read(buffer);
                                }
                                catch (IOException ioe)
                                {
                                    logger.info("Failed to read from pipe.");
                                }
                            }
                            else
                            {
                                /*
                                 * We need this in order to de-register the
                                 * channel from the selector.
                                 */
                                key.cancel();

                                readFromChannel((SocketChannel) selectedChannel);
                                channels.remove(selectedChannel);
                            }
                        }
                    }
                    selectedKeys.clear();
                }

            } //while(true)


            //we are all done, clean up.
            synchronized (newChannels)
            {
                for (SocketChannel channel : newChannels)
                {
                    try
                    {
                        channel.close();
                    }
                    catch (IOException ioe)
                    {}
                }
                newChannels.clear();
            }

            for (SocketChannel channel : channels.keySet())
            {
                try
                {
                    channel.close();
                }
                catch (IOException ioe)
                {}
            }

            try
            {
                selector.close();
            }
            catch (IOException ioe)
            {}
        }

        /**
         * Checks {@link #channels} for channels which have been added over
         * {@link #READ_TIMEOUT} milliseconds ago and closes them.
         */
        private void cleanup()
        {
            long now = System.currentTimeMillis();
            toRemove.clear();

            for (Map.Entry<SocketChannel, Long> entry : channels.entrySet())
            {
                if (now - entry.getValue() > READ_TIMEOUT)
                {
                    SocketChannel channel = entry.getKey();

                    toRemove.add(channel);
                    logger.info("Read timeout for socket: " + channel);

                    try
                    {
                        channel.close();
                    }
                    catch (IOException ioe)
                    {
                        logger.info("Failed to close channel: " + ioe);
                    }

                }
            }

            for (SocketChannel channel : toRemove)
            {
                channels.remove(channel);
            }
        }

        /**
         * Tries to read a STUN message from a specific <tt>SocketChannel</tt>
         * and handles the channel accordingly.
         *
         * If a STUN message is successfully read, and it contains a USERNAME
         * attribute, the local "ufrag" is extracted from the attribute value
         * and the socket is passed on to the <tt>Component</tt> that
         * this <tt>MultiplexingTcpHostHarvester</tt> has associated with that
         * "ufrag".
         * @param channel the <tt>SocketChannel</tt> to read from.
         */
        private void readFromChannel(SocketChannel channel)
        {
            try
            {
                // re-enable blocking mode, so that we can read from the
                // socket's input stream
                channel.configureBlocking(true);

                // read an RFC4571 frame into datagramPacket
                Socket socket = channel.socket();
                DelegatingSocket.receiveFromNetwork(
                        datagramPacket,
                        socket.getInputStream(),
                        socket.getLocalAddress(),
                        socket.getLocalPort());

                // Does this look like a STUN binding request?
                // What's the username?
                Message stunMessage
                        = Message.decode(datagramPacket.getData(),
                                         (char) datagramPacket.getOffset(),
                                         (char) datagramPacket.getLength());

                if (stunMessage.getMessageType() != Message.BINDING_REQUEST)
                    throw new ReadThreadException("Not a binding request");

                UsernameAttribute usernameAttribute
                        = (UsernameAttribute)
                        stunMessage.getAttribute(Attribute.USERNAME);
                if (usernameAttribute == null)
                    throw new ReadThreadException(
                            "No USERNAME attribute present.");

                String usernameString
                        = new String(usernameAttribute.getUsername());
                String localUfrag = usernameString.split(":")[0];
                Component component = getComponent(localUfrag);
                if (component == null)
                    throw new ReadThreadException("No component found.");


                //phew, finally
                handSocketToComponent(socket, component);
            }
            catch (IOException e)
            {
                logger.info("Failed to read from socket: " + e);
            }
            catch (StunException e)
            {
                logger.info("Failed to read from socket: " + e);
            }
            catch (ReadThreadException e)
            {
                logger.info("Failed to read from socket: " + e);
            }
            finally
            {
                channels.remove(channel);
            }
        }

        /**
         * Makes <tt>socket</tt> available to <tt>component</tt>.
         * @param socket the <tt>Socket</tt>.
         * @param component the <tt>Component</tt>.
         */
        private void handSocketToComponent(Socket socket, Component component)
        {
            IceProcessingState state
                    = component.getParentStream().getParentAgent().getState();
            if (!IceProcessingState.WAITING.equals(state)
                    && !IceProcessingState.RUNNING.equals(state))
            {
                logger.info("Not adding a socket to an ICE agent with state "
                                + state);
                return;
            }

            // Socket to add to the candidate
            IceSocketWrapper candidateSocket = null;

            // STUN-only filtered socket to add to the StunStack
            IceSocketWrapper stunSocket = null;

            try
            {
                MultiplexingSocket multiplexing = new MultiplexingSocket(socket);
                candidateSocket = new IceTcpSocketWrapper(multiplexing);

                stunSocket
                    = new IceTcpSocketWrapper(
                        multiplexing.getSocket(new StunDatagramPacketFilter()));
            }
            catch (IOException ioe)
            {
                logger.info("Failed to create sockets: " + ioe);
            }

            TcpHostCandidate candidate = findCandidate(component, socket);
            if (candidate != null)
            {
                component.getParentStream().getParentAgent()
                        .getStunStack().addSocket(stunSocket);
                candidate.addSocket(candidateSocket);

                // the socket is not our responsibility anymore. It is up to
                // the candidate/component to close/free it.
            }
            else
            {
                logger.info("Failed to find the local candidate for socket: "
                                    + socket);
                try
                {
                    socket.close();
                }
                catch (IOException ioe)
                {}
            }

        }

        /**
         * Searches among the local candidates of <tt>Component</tt> for a
         * <tt>TcpHostCandidate</tt> with the same transport address as the
         * local transport address of <tt>socket</tt>.
         *
         * We expect to find such a candidate, which has been added by this
         * <tt>MultiplexingTcpHostHarvester</tt> while harvesting.
         *
         * @param component the <tt>Component</tt> to search.
         * @param socket the <tt>Socket</tt> to match the local transport
         * address of.
         * @return a <tt>TcpHostCandidate</tt> among the local candidates of
         * <tt>Component</tt> with the same transport address as the local
         * address of <tt>Socket</tt>, or <tt>null</tt> if no such candidate
         * exists.
         */
        private TcpHostCandidate findCandidate(Component component, Socket socket)
        {
            InetAddress localAddress = socket.getLocalAddress();
            int localPort = socket.getLocalPort();

            for (LocalCandidate candidate : component.getLocalCandidates())
            {
                TransportAddress transportAddress
                        = candidate.getTransportAddress();
                if (candidate instanceof TcpHostCandidate
                        && Transport.TCP.equals(transportAddress.getTransport())
                        && localPort == transportAddress.getPort()
                        && localAddress.equals(transportAddress.getAddress()))
                {
                    return (TcpHostCandidate) candidate;
                }
            }
            return null;
        }

        /**
         * Adds the channels from {@link #newChannels} to {@link #channels}
         * and registers them in {@link #selector}.
         */
        private void checkForNewChannels()
        {
            synchronized (newChannels)
            {
                for (SocketChannel channel : newChannels)
                {
                    try
                    {
                        channel.configureBlocking(false);
                        channel.register(selector, SelectionKey.OP_READ);
                    }
                    catch (IOException ioe)
                    {
                        logger.info("Failed to register channel: " + ioe);
                        try
                        {
                            channel.close();
                        }
                        catch (IOException ioe2)
                        {}
                    }

                    channels.put(channel, System.currentTimeMillis());
                }
                newChannels.clear();
            }
        }
    }

    /**
     * An exception used internally by
     * {@link org.ice4j.ice.harvest.MultiplexingTcpHostHarvester.ReadThread}.
     */
    @SuppressWarnings("serial")
    private class ReadThreadException
        extends Exception
    {
        private ReadThreadException(String s)
        {
            super(s);
        }
    }
}
