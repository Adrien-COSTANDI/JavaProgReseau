package chatint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

public class ServerChatInt {

	private static final int BUFFER_SIZE = 1_024;
	private static final Logger logger = Logger.getLogger(ServerChatInt.class.getName());
	private final ServerSocketChannel serverSocketChannel;
	private final Selector selector;
	public ServerChatInt(int port) throws IOException {
		serverSocketChannel = ServerSocketChannel.open();
		serverSocketChannel.bind(new InetSocketAddress(port));
		selector = Selector.open();
	}

	public static void main(String[] args) throws NumberFormatException, IOException {
		if (args.length != 1) {
			usage();
			return;
		}
		new ServerChatInt(Integer.parseInt(args[0])).launch();
	}

	private static void usage() {
		System.out.println("Usage : ServerSumBetter port");
	}

	public void launch() throws IOException {
		serverSocketChannel.configureBlocking(false);
		serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
		while (!Thread.interrupted()) {
			System.out.println("Starting select");
			try {
				selector.select(this::treatKey);
			} catch (UncheckedIOException tunneled) {
				throw tunneled.getCause();
			}
			System.out.println("Select finished");
		}
	}

	private void treatKey(SelectionKey key) {
		try {
			if (key.isValid() && key.isAcceptable()) {
				doAccept(key);
			}
		} catch (IOException ioe) {
			// lambda call in select requires to tunnel IOException
			throw new UncheckedIOException("Entrance door is on fire!", ioe);
		}
		try {
			if (key.isValid() && key.isWritable()) {
				((Context) key.attachment()).doWrite();
			}
			if (key.isValid() && key.isReadable()) {
				((Context) key.attachment()).doRead();
			}
		} catch (IOException e) {
			logger.log(Level.INFO, "Connection closed with client due to IOException", e);
			silentlyClose(key);
		}
	}

	private void doAccept(SelectionKey key) throws IOException {
		var client = serverSocketChannel.accept();

		if (client == null) {
			return;
		}
		client.configureBlocking(false);
		var selectionKey = client.register(selector, OP_READ);
		selectionKey.attach(new Context(this, selectionKey));

		System.out.println("new client: " + client.getRemoteAddress());
	}

	private void silentlyClose(SelectionKey key) {
		Channel sc = key.channel();
		try {
			sc.close();
		} catch (IOException e) {
			// ignore exception
		}
	}

	/**
	 * Add a message to all connected clients queue
	 *
	 * @param msg
	 */
	private void broadcast(Integer msg) {
		selector.keys().stream()
				.filter(key -> !(key.channel() instanceof ServerSocketChannel))
				.forEach(client -> {
					var context = (Context) client.attachment();
					context.queueMessage(msg);
				});
	}

	static private class Context {
		private final SelectionKey key;
		private final SocketChannel sc;
		private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
		private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
		private final ArrayDeque<Integer> queue = new ArrayDeque<>();
		private final ServerChatInt server; // we could also have Context as an instance class, which would naturally
											// give access to ServerChatInt.this
		private boolean closed = false;

		private Context(ServerChatInt server, SelectionKey key) {
			this.key = key;
			this.sc = (SocketChannel) key.channel();
			this.server = server;
		}

		/**
		 * Process the content of bufferIn
		 *
		 * The convention is that bufferIn is in write-mode before the call to process and
		 * after the call
		 *
		 */
		private void processIn() {
			if (bufferIn.position() < Integer.BYTES) {
				return;
			}

			bufferIn.flip();
			while (bufferIn.remaining() >= Integer.BYTES) {
				server.broadcast(bufferIn.getInt());
			}
			bufferIn.compact();
		}

		/**
		 * Add a message to the message queue, tries to fill bufferOut and updateInterestOps
		 *
		 * @param msg
		 */
		public void queueMessage(Integer msg) {
			queue.addLast(msg);
			processOut();
			updateInterestOps();
		}

		/**
		 * Try to fill bufferOut from the message queue
		 *
		 */
		private void processOut() {
			while (bufferOut.remaining() >= Integer.BYTES && !queue.isEmpty()) {
				bufferOut.putInt(queue.poll());
			}
		}

		/**
		 * Update the interestOps of the key looking only at values of the boolean
		 * closed and of both ByteBuffers.
		 *
		 * The convention is that both buffers are in write-mode before the call to
		 * updateInterestOps and after the call. Also it is assumed that process has
		 * been be called just before updateInterestOps.
		 */
		private void updateInterestOps() {
			var ops = 0; // à la base

			if (!closed && bufferIn.hasRemaining()) { // si je peux encore lire
				ops |= OP_READ;
			}

			if (bufferOut.position() != 0) { // s'il me reste des choses à écrire
				ops |= OP_WRITE;
			}

			if (ops == 0) { // au final si j'ai rien à faire
				silentlyClose();
				return;
			}

			key.interestOps(ops);
		}

		private void silentlyClose() {
			try {
				sc.close();
			} catch (IOException e) {
				// ignore exception
			}
		}

		/**
		 * Performs the read action on sc
		 *
		 * The convention is that both buffers are in write-mode before the call to
		 * doRead and after the call
		 *
		 * @throws IOException
		 */
		private void doRead() throws IOException {
			if (sc.read(bufferIn) == -1) {
				closed = true;
			}
			processIn();
			updateInterestOps();
		}

		/**
		 * Performs the write action on sc
		 *
		 * The convention is that both buffers are in write-mode before the call to
		 * doWrite and after the call
		 *
		 * @throws IOException
		 */
		private void doWrite() throws IOException {
			bufferOut.flip();
			sc.write(bufferOut);
			bufferOut.compact();
			processOut();
			updateInterestOps();
		}

	}
}