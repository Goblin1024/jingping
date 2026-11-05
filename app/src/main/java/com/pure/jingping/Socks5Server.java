package com.pure.jingping;

import android.content.Context;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

final class Socks5Server implements Closeable {
    private static final String TAG = "JingPingProxy";
    private static final int CONNECT = 1;
    private static final int UDP_ASSOCIATE = 3;
    private static final int MAX_UDP_PACKET = 65535;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int PEEK_SIZE = 32768;

    interface Host {
        boolean protect(Socket socket);

        boolean protect(DatagramSocket socket);

        void bind(Socket socket);

        void bind(DatagramSocket socket);

        List<InetAddress> dnsServers();
    }

    private final Context context;
    private final Host host;
    private volatile DnsRules rules;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private ServerSocket listener;

    Socks5Server(Context context, Host host, DnsRules rules) {
        this.context = context;
        this.host = host;
        this.rules = rules;
    }

    int start() throws IOException {
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        Thread thread = new Thread(this::acceptLoop, "jingping-socks");
        thread.start();
        Log.i(TAG, "local SOCKS5 listening on " + listener.getLocalPort());
        return listener.getLocalPort();
    }

    void setRules(DnsRules rules) {
        this.rules = rules;
    }

    @Override
    public void close() {
        closed = true;
        if (listener != null) {
            try {
                listener.close();
            } catch (Exception ignored) {
            }
            listener = null;
        }
        for (Socket socket : new ArrayList<>(sockets)) {
            closeQuietly(socket);
        }
        sockets.clear();
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = listener.accept();
                client.setTcpNoDelay(true);
                sockets.add(client);
                workers.execute(() -> handleClient(client));
            } catch (Exception e) {
                if (!closed) {
                    Log.w(TAG, "SOCKS accept failed", e);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        Socket remote = null;
        try {
            client.setSoTimeout(15000);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();

            int version = input.read();
            int methodCount = input.read();
            if (version != 5 || methodCount < 0 || methodCount > 16) {
                return;
            }
            byte[] methods = new byte[methodCount];
            readFully(input, methods, 0, methods.length);
            boolean noAuth = false;
            for (byte method : methods) {
                if ((method & 0xff) == 0) {
                    noAuth = true;
                    break;
                }
            }
            if (!noAuth) {
                output.write(new byte[]{5, (byte) 0xff});
                output.flush();
                return;
            }
            output.write(new byte[]{5, 0});
            output.flush();

            int requestVersion = input.read();
            int command = input.read();
            input.read();
            if (requestVersion != 5) {
                return;
            }
            Target target = readTarget(input);
            if (target == null) {
                writeReply(output, 8);
                return;
            }

            if (command == CONNECT) {
                if (target.hostname != null && rules.isBlocked(target.hostname)) {
                    recordQuery(target.hostname, true);
                    writeReply(output, 2);
                    return;
                }
                if (target.hostname != null) {
                    recordQuery(target.hostname, false);
                }
                remote = new Socket();
                remote.setTcpNoDelay(true);
                host.protect(remote);
                host.bind(remote);
                try {
                    remote.connect(target.resolve(), CONNECT_TIMEOUT_MS);
                } catch (IOException e) {
                    writeReply(output, 5);
                    Log.w(TAG, "connect failed " + target.display() + ": " + e.getMessage());
                    return;
                }
                writeReply(output, 0);
                if (target.port == 443 || target.port == 80) {
                    relayInspectable(client, remote);
                } else {
                    relay(client, remote);
                }
            } else if (command == UDP_ASSOCIATE) {
                handleUdpAssociate(client, input, output);
            } else {
                writeReply(output, 7);
            }
        } catch (Exception e) {
            if (!closed) {
                Log.w(TAG, "SOCKS client failed: " + e);
            }
            closeQuietly(client);
            closeQuietly(remote);
        } finally {
            sockets.remove(client);
        }
    }

    private void relayInspectable(Socket client, Socket remote) {
        byte[] prefix = new byte[0];
        try {
            client.setSoTimeout(3500);
            prefix = readPrefix(client.getInputStream());
            String host = parseTlsSni(prefix);
            if (host == null) {
                host = parseHttpHost(prefix);
            }
            if (host != null && rules.isBlocked(host)) {
                recordQuery(host, true);
                closeQuietly(client);
                closeQuietly(remote);
                return;
            }
            if (host != null) {
                recordQuery(host, false);
            }
        } catch (Exception e) {
            if (!(e instanceof SocketTimeoutException) && !closed) {
                Log.w(TAG, "request inspection failed: " + e);
            }
        }

        try {
            client.setSoTimeout(0);
        } catch (Exception ignored) {
        }
        relay(client, remote, prefix);
    }

    private byte[] readPrefix(InputStream input) throws IOException {
        byte[] buffer = new byte[PEEK_SIZE];
        int read = input.read(buffer);
        if (read <= 0) {
            return new byte[0];
        }
        byte[] result = new byte[read];
        System.arraycopy(buffer, 0, result, 0, read);
        return result;
    }

    private void relay(Socket client, Socket remote) {
        relay(client, remote, new byte[0]);
    }

    private void relay(Socket client, Socket remote, byte[] prefix) {
        Thread upload = new Thread(() -> {
            try {
                if (prefix.length > 0) {
                    remote.getOutputStream().write(prefix);
                    remote.getOutputStream().flush();
                }
                pipe(client, remote);
            } catch (Exception ignored) {
                closeQuietly(client);
                closeQuietly(remote);
            }
        }, "jingping-tcp-up");
        upload.start();
        pipe(remote, client);
        closeQuietly(client);
        closeQuietly(remote);
        try {
            upload.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String parseTlsSni(byte[] data) {
        if (data == null || data.length < 5 || data[0] != 0x16) {
            return null;
        }
        int recordLength = DnsCodec.readU16(data, 3);
        int limit = Math.min(data.length, 5 + recordLength);
        int offset = 5;
        if (offset + 4 > limit || data[offset] != 0x01) {
            return null;
        }
        offset += 4;
        if (offset + 2 + 32 > limit) {
            return null;
        }
        offset += 2 + 32;
        if (offset >= limit) {
            return null;
        }
        int sessionLength = data[offset++] & 0xff;
        offset += sessionLength;
        if (offset + 2 > limit) {
            return null;
        }
        int cipherLength = DnsCodec.readU16(data, offset);
        offset += 2 + cipherLength;
        if (offset >= limit) {
            return null;
        }
        int compressionLength = data[offset++] & 0xff;
        offset += compressionLength;
        if (offset + 2 > limit) {
            return null;
        }
        int extensionsLength = DnsCodec.readU16(data, offset);
        offset += 2;
        int extensionsEnd = Math.min(limit, offset + extensionsLength);

        while (offset + 4 <= extensionsEnd) {
            int type = DnsCodec.readU16(data, offset);
            int length = DnsCodec.readU16(data, offset + 2);
            offset += 4;
            if (offset + length > extensionsEnd) {
                return null;
            }
            if (type == 0 && length >= 5) {
                int listLength = DnsCodec.readU16(data, offset);
                int cursor = offset + 2;
                int listEnd = Math.min(offset + length, cursor + listLength);
                while (cursor + 3 <= listEnd) {
                    int nameType = data[cursor++] & 0xff;
                    int nameLength = DnsCodec.readU16(data, cursor);
                    cursor += 2;
                    if (cursor + nameLength > listEnd) {
                        return null;
                    }
                    if (nameType == 0 && nameLength > 0) {
                        return new String(data, cursor, nameLength,
                                java.nio.charset.StandardCharsets.US_ASCII);
                    }
                    cursor += nameLength;
                }
            }
            offset += length;
        }
        return null;
    }

    private String parseHttpHost(byte[] data) {
        if (data == null || data.length < 5) {
            return null;
        }
        String request = new String(data, java.nio.charset.StandardCharsets.US_ASCII);
        String[] lines = request.split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("GET ")
                && !lines[0].startsWith("POST ")
                && !lines[0].startsWith("HEAD ")
                && !lines[0].startsWith("CONNECT ")) {
            return null;
        }
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("host")) {
                String host = line.substring(colon + 1).trim();
                int port = host.lastIndexOf(':');
                if (port > 0 && host.indexOf(']') < 0) {
                    host = host.substring(0, port);
                }
                return host;
            }
        }
        return null;
    }

    private void pipe(Socket from, Socket to) {
        byte[] buffer = new byte[32768];
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private void handleUdpAssociate(Socket client, InputStream input, OutputStream output)
            throws IOException {
        DatagramSocket relay = new DatagramSocket(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        host.protect(relay);
        UdpSession session = new UdpSession(relay);
        byte[] address = relay.getLocalAddress().getAddress();
        byte[] reply = new byte[4 + address.length + 2];
        reply[0] = 5;
        reply[1] = 0;
        reply[2] = 0;
        reply[3] = (byte) (address.length == 4 ? 1 : 4);
        System.arraycopy(address, 0, reply, 4, address.length);
        DnsCodec.putU16(reply, 4 + address.length, relay.getLocalPort());
        output.write(reply);
        output.flush();

        Thread udpThread = new Thread(session, "jingping-socks-udp");
        udpThread.start();
        client.setSoTimeout(0);
        while (!closed && input.read() >= 0) {
            // The TCP control connection defines the lifetime of UDP ASSOCIATE.
        }
        session.close();
        try {
            udpThread.join(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(client);
    }

    private void writeReply(OutputStream output, int code) throws IOException {
        output.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    private Target readTarget(InputStream input) throws IOException {
        int type = input.read();
        if (type == 1) {
            byte[] raw = new byte[4];
            readFully(input, raw, 0, raw.length);
            return new Target(InetAddress.getByAddress(raw), null, readPort(input));
        }
        if (type == 4) {
            byte[] raw = new byte[16];
            readFully(input, raw, 0, raw.length);
            return new Target(InetAddress.getByAddress(raw), null, readPort(input));
        }
        if (type == 3) {
            int length = input.read();
            if (length <= 0) {
                return null;
            }
            byte[] raw = new byte[length];
            readFully(input, raw, 0, raw.length);
            return new Target(null, new String(raw, java.nio.charset.StandardCharsets.US_ASCII),
                    readPort(input));
        }
        return null;
    }

    private int readPort(InputStream input) throws IOException {
        int high = input.read();
        int low = input.read();
        if (high < 0 || low < 0) {
            throw new IOException("short SOCKS address");
        }
        return (high << 8) | low;
    }

    private void readFully(InputStream input, byte[] target, int offset, int length)
            throws IOException {
        int done = 0;
        while (done < length) {
            int count = input.read(target, offset + done, length - done);
            if (count < 0) {
                throw new IOException("unexpected end of SOCKS stream");
            }
            done += count;
        }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private void recordQuery(String domain, boolean blocked) {
        Prefs.recordDnsQuery(context, blocked, domain);
        DnsQueryLog.record(domain, blocked);
        if (blocked) {
            Log.i(TAG, "blocked " + domain);
        }
    }

    private final class UdpSession implements Runnable, Closeable {
        private final DatagramSocket clientSocket;
        private final DatagramSocket upstreamSocket;
        private final AtomicReference<InetSocketAddress> clientAddress =
                new AtomicReference<>();
        private volatile boolean sessionClosed;

        UdpSession(DatagramSocket clientSocket) throws IOException {
            this.clientSocket = clientSocket;
            this.upstreamSocket = new DatagramSocket();
            host.protect(upstreamSocket);
            host.bind(upstreamSocket);
            clientSocket.setSoTimeout(180);
            upstreamSocket.setSoTimeout(180);
        }

        @Override
        public void run() {
            Thread replies = new Thread(this::receiveReplies, "jingping-udp-down");
            replies.start();
            byte[] buffer = new byte[MAX_UDP_PACKET];
            try {
                while (!closed && !sessionClosed) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        clientSocket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    clientAddress.set(new InetSocketAddress(packet.getAddress(), packet.getPort()));
                    UdpRequest request = parseUdpRequest(packet.getData(), packet.getLength());
                    if (request == null) {
                        continue;
                    }
                    if (request.port == 53) {
                        byte[] query = request.payload;
                        Target target = request.target;
                        workers.execute(() -> handleDnsUdp(query, target));
                    } else {
                        try {
                            InetSocketAddress resolved = request.target.resolve();
                            upstreamSocket.send(new DatagramPacket(
                                    request.payload, request.payload.length,
                                    resolved.getAddress(), resolved.getPort()));
                        } catch (Exception e) {
                            Log.w(TAG, "UDP forward failed", e);
                        }
                    }
                }
            } catch (Exception e) {
                if (!sessionClosed && !closed) {
                    Log.w(TAG, "UDP client loop failed", e);
                }
            } finally {
                close();
                try {
                    replies.join(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void receiveReplies() {
            byte[] buffer = new byte[MAX_UDP_PACKET];
            while (!closed && !sessionClosed) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    upstreamSocket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (Exception e) {
                    if (!closed && !sessionClosed) {
                        Log.w(TAG, "UDP receive failed", e);
                    }
                    break;
                }
                InetSocketAddress destination = clientAddress.get();
                if (destination == null) {
                    continue;
                }
                byte[] header = udpHeader(packet.getAddress(), packet.getPort());
                byte[] response = new byte[header.length + packet.getLength()];
                System.arraycopy(header, 0, response, 0, header.length);
                System.arraycopy(packet.getData(), packet.getOffset(),
                        response, header.length, packet.getLength());
                try {
                    synchronized (clientSocket) {
                        clientSocket.send(new DatagramPacket(
                                response, response.length, destination));
                    }
                } catch (Exception e) {
                    if (!closed && !sessionClosed) {
                        Log.w(TAG, "UDP reply failed", e);
                    }
                }
            }
        }

        private void handleDnsUdp(byte[] query, Target target) {
            DnsCodec.Question question = DnsCodec.parseQuestion(query);
            byte[] response;
            if (question != null && rules.isBlocked(question.name)) {
                recordQuery(question.name, true);
                response = DnsCodec.errorResponse(query, question, 3);
            } else {
                if (question != null) {
                    recordQuery(question.name, false);
                }
                response = forwardDns(query, target.address);
                if (response == null && question != null) {
                    response = DnsCodec.errorResponse(query, question, 2);
                }
            }
            if (response == null) {
                return;
            }
            InetSocketAddress destination = clientAddress.get();
            if (destination == null || sessionClosed) {
                return;
            }
            InetAddress source = target.address;
            if (source == null) {
                try {
                    source = target.resolve().getAddress();
                } catch (Exception ignored) {
                }
            }
            if (source == null) {
                return;
            }
            byte[] header = udpHeader(source, 53);
            byte[] packet = new byte[header.length + response.length];
            System.arraycopy(header, 0, packet, 0, header.length);
            System.arraycopy(response, 0, packet, header.length, response.length);
            try {
                synchronized (clientSocket) {
                    clientSocket.send(new DatagramPacket(packet, packet.length, destination));
                }
            } catch (Exception e) {
                if (!sessionClosed) {
                    Log.w(TAG, "DNS reply failed", e);
                }
            }
        }

        private byte[] forwardDns(byte[] query, InetAddress requested) {
            List<InetAddress> candidates = new ArrayList<>();
            if (requested != null) {
                candidates.add(requested);
            }
            for (InetAddress address : host.dnsServers()) {
                if (!candidates.contains(address)) {
                    candidates.add(address);
                }
            }
            int attempts = 0;
            for (InetAddress address : candidates) {
                if (attempts++ >= 4) {
                    break;
                }
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    host.protect(socket);
                    host.bind(socket);
                    socket.connect(new InetSocketAddress(address, 53));
                    socket.setSoTimeout(1800);
                    socket.send(new DatagramPacket(query, query.length));
                    byte[] responseBuffer = new byte[MAX_UDP_PACKET];
                    DatagramPacket response = new DatagramPacket(
                            responseBuffer, responseBuffer.length);
                    socket.receive(response);
                    if (response.getLength() >= 12
                            && response.getData()[0] == query[0]
                            && response.getData()[1] == query[1]
                            && (response.getData()[2] & 0x80) != 0) {
                        byte[] result = new byte[response.getLength()];
                        System.arraycopy(response.getData(), 0, result, 0, result.length);
                        return result;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "DNS upstream " + address.getHostAddress() + " failed: "
                            + e.getMessage());
                } finally {
                    if (socket != null) {
                        socket.close();
                    }
                }
            }
            return null;
        }

        private UdpRequest parseUdpRequest(byte[] data, int length) {
            if (length < 10 || data[0] != 0 || data[1] != 0 || data[2] != 0) {
                return null;
            }
            int offset = 4;
            int type = data[3] & 0xff;
            InetAddress address;
            String hostname = null;
            try {
                if (type == 1) {
                    if (length < offset + 4 + 2) {
                        return null;
                    }
                    byte[] raw = new byte[4];
                    System.arraycopy(data, offset, raw, 0, 4);
                    offset += 4;
                    address = InetAddress.getByAddress(raw);
                } else if (type == 4) {
                    if (length < offset + 16 + 2) {
                        return null;
                    }
                    byte[] raw = new byte[16];
                    System.arraycopy(data, offset, raw, 0, 16);
                    offset += 16;
                    address = InetAddress.getByAddress(raw);
                } else if (type == 3) {
                    int hostLength = data[offset++] & 0xff;
                    if (hostLength <= 0 || length < offset + hostLength + 2) {
                        return null;
                    }
                    hostname = new String(data, offset, hostLength,
                            java.nio.charset.StandardCharsets.US_ASCII);
                    offset += hostLength;
                    address = null;
                } else {
                    return null;
                }
                int port = DnsCodec.readU16(data, offset);
                offset += 2;
                if (offset > length) {
                    return null;
                }
                byte[] payload = new byte[length - offset];
                System.arraycopy(data, offset, payload, 0, payload.length);
                return new UdpRequest(new Target(address, hostname, port), port, payload);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void close() {
            sessionClosed = true;
            try {
                clientSocket.close();
            } catch (Exception ignored) {
            }
            try {
                upstreamSocket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] udpHeader(InetAddress address, int port) {
        byte[] raw = address.getAddress();
        byte[] header = new byte[4 + raw.length + 2];
        header[0] = 0;
        header[1] = 0;
        header[2] = 0;
        header[3] = (byte) (raw.length == 4 ? 1 : 4);
        System.arraycopy(raw, 0, header, 4, raw.length);
        DnsCodec.putU16(header, 4 + raw.length, port);
        return header;
    }

    private static final class Target {
        final InetAddress address;
        final String hostname;
        final int port;

        Target(InetAddress address, String hostname, int port) {
            this.address = address;
            this.hostname = hostname;
            this.port = port;
        }

        InetSocketAddress resolve() throws IOException {
            if (address != null) {
                return new InetSocketAddress(address, port);
            }
            return new InetSocketAddress(InetAddress.getByName(hostname), port);
        }

        String display() {
            String host = hostname != null ? hostname : String.valueOf(address);
            return host + ":" + port;
        }
    }

    private static final class UdpRequest {
        final Target target;
        final int port;
        final byte[] payload;

        UdpRequest(Target target, int port, byte[] payload) {
            this.target = target;
            this.port = port;
            this.payload = payload;
        }
    }
}
