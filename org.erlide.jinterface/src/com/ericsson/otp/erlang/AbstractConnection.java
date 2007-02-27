/* ``The contents of this file are subject to the Erlang Public License,
 * Version 1.1, (the "License"); you may not use this file except in
 * compliance with the License. You should have received a copy of the
 * Erlang Public License along with this software. If not, it can be
 * retrieved via the world wide web at http://www.erlang.org/.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Initial Developer of the Original Code is Ericsson Utvecklings AB.
 * Portions created by Ericsson are Copyright 1999, Ericsson Utvecklings
 * AB. All Rights Reserved.''
 *
 *     $Id$
 */
package com.ericsson.otp.erlang;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Random;

/**
 * Maintains a connection between a Java process and a remote Erlang, Java or C
 * node. The object maintains connection state and allows data to be sent to and
 * received from the peer.
 * 
 * <p>
 * This abstract class provides the neccesary methods to maintain the actual
 * connection and encode the messages and headers in the proper format according
 * to the Erlang distribution protocol. Subclasses can use these methods to
 * provide a more or less transparent communication channel as desired.
 * </p>
 * 
 * <p>
 * Note that no receive methods are provided. Subclasses must provide methods
 * for message delivery, and may implement their own receive methods.
 * <p>
 * 
 * <p>
 * If an exception occurs in any of the methods in this class, the connection
 * will be closed and must be reopened in order to resume communication with the
 * peer. This will be indicated to the subclass by passing the exception to its
 * delivery() method.
 * </p>
 * 
 * <p>
 * The System property OtpConnection.trace can be used to change the initial
 * trace level setting for all connections. Normally the initial trace level is
 * 0 and connections are not traced unless
 * {@link #setTraceLevel setTraceLevel()} is used to change the setting for a
 * particular connection. OtpConnection.trace can be used to turn on tracing by
 * default for all connections.
 * </p>
 */
public abstract class AbstractConnection extends Thread {

	protected static final int headerLen = 2048; // more than enough

	protected static final byte passThrough = (byte) 0x70;

	protected static final byte version = (byte) 0x83;

	// Erlang message header tags
	protected static final int linkTag = 1;

	protected static final int sendTag = 2;

	protected static final int exitTag = 3;

	protected static final int unlinkTag = 4;

	protected static final int nodeLinkTag = 5;

	protected static final int regSendTag = 6;

	protected static final int groupLeaderTag = 7;

	protected static final int exit2Tag = 8;

	protected static final int sendTTTag = 12;

	protected static final int exitTTTag = 13;

	protected static final int regSendTTTag = 16;

	protected static final int exit2TTTag = 18;

	// MD5 challenge messsage tags
	protected static final int ChallengeReply = 'r';

	protected static final int ChallengeAck = 'a';

	protected static final int ChallengeStatus = 's';

	private volatile boolean done = false;

	protected boolean connected = false; // connection status

	protected Socket socket; // communication channel

	protected OtpPeer peer; // who are we connected to

	protected OtpLocalNode self; // this nodes id

	String name; // local name of this connection

	protected boolean cookieOk = false; // already checked the cookie for this

	// connection

	protected boolean sendCookie = true; // Send cookies in messages?

	// tracelevel constants
	protected int traceLevel = 0;

	protected static int defaultLevel = 0;

	protected static int sendThreshold = 1;

	protected static int ctrlThreshold = 2;

	protected static int handshakeThreshold = 3;

	protected static Random random = null;

	static {
		// trace this connection?
		final String trace = System.getProperties().getProperty(
				"OtpConnection.trace");
		try {
			if (trace != null) {
				defaultLevel = Integer.valueOf(trace).intValue();
			}
		} catch (final NumberFormatException e) {
			defaultLevel = 0;
		}
		random = new Random();
	}

	// private AbstractConnection() {
	// }

	/*
	 * Accept an incoming connection from a remote node. Used by
	 * {@link OtpSelf#accept() OtpSelf.accept()} to create a connection based on
	 * data received when handshaking with the peer node, when the remote node
	 * is the connection intitiator.
	 * 
	 * @exception java.io.IOException if it was not possible to connect to the
	 * peer.
	 * 
	 * @exception OtpAuthException if handshake resulted in an authentication
	 * error
	 */
	protected AbstractConnection(OtpLocalNode self, Socket s)
			throws IOException, OtpAuthException {
		this.self = self;
		peer = new OtpPeer();
		socket = s;

		socket.setTcpNoDelay(true);

		traceLevel = defaultLevel;
		this.setDaemon(true);

		if (traceLevel >= handshakeThreshold) {
			System.out.println("<- ACCEPT FROM " + s.getInetAddress() + ":"
					+ s.getPort());
		}

		// get his info
		recvName(peer);

		// now find highest common dist value
		if ((peer.proto != self.proto) || (self.distHigh < peer.distLow)
				|| (self.distLow > peer.distHigh)) {
			close();
			throw new IOException(
					"No common protocol found - cannot accept connection");
		}
		// highest common version: min(peer.distHigh, self.distHigh)
		peer.distChoose = (peer.distHigh > self.distHigh ? self.distHigh
				: peer.distHigh);

		doAccept();
		name = peer.node();
	}

	/*
	 * Intiate and open a connection to a remote node.
	 * 
	 * @exception java.io.IOException if it was not possible to connect to the
	 * peer.
	 * 
	 * @exception OtpAuthException if handshake resulted in an authentication
	 * error.
	 */
	protected AbstractConnection(OtpLocalNode self, OtpPeer other)
			throws IOException, OtpAuthException {
		peer = other;
		this.self = self;
		socket = null;
		int port;

		traceLevel = defaultLevel;
		this.setDaemon(true);

		// now get a connection between the two...
		port = OtpEpmd.lookupPort(peer);

		// now find highest common dist value
		if ((peer.proto != self.proto) || (self.distHigh < peer.distLow)
				|| (self.distLow > peer.distHigh)) {
			throw new IOException("No common protocol found - cannot connect");
		}

		// highest common version: min(peer.distHigh, self.distHigh)
		peer.distChoose = (peer.distHigh > self.distHigh ? self.distHigh
				: peer.distHigh);

		doConnect(port);

		name = peer.node();
		connected = true;
	}

	/**
	 * Deliver communication exceptions to the recipient.
	 */
	public abstract void deliver(Exception e);

	/**
	 * Deliver messages to the recipient.
	 */
	public abstract void deliver(OtpMsg msg);

	/**
	 * Send a pre-encoded message to a named process on a remote node.
	 * 
	 * @param dest
	 *            the name of the remote process.
	 * @param payload
	 *            the encoded message to send.
	 * 
	 * @exception java.io.IOException
	 *                if the connection is not active or a communication error
	 *                occurs.
	 */
	protected void sendBuf(OtpErlangPid from, String dest,
			OtpOutputStream payload) throws IOException {
		if (!connected) {
			throw new IOException("Not connected");
		}
		final OtpOutputStream header = new OtpOutputStream(headerLen);

		// preamble: 4 byte length + "passthrough" tag + version
		header.write4BE(0); // reserve space for length
		header.write1(passThrough);
		header.write1(version);

		// header info
		header.write_tuple_head(4);
		header.write_long(regSendTag);
		header.write_any(from);
		if (sendCookie) {
			header.write_atom(self.cookie());
		} else {
			header.write_atom("");
		}
		header.write_atom(dest);

		// version for payload
		header.write1(version);

		// fix up length in preamble
		header.poke4BE(0, header.count() + payload.count() - 4);

		do_send(header, payload);
	}

	/**
	 * Send a pre-encoded message to a process on a remote node.
	 * 
	 * @param dest
	 *            the Erlang PID of the remote process.
	 * @param msg
	 *            the encoded message to send.
	 * 
	 * @exception java.io.IOException
	 *                if the connection is not active or a communication error
	 *                occurs.
	 */
	protected void sendBuf(OtpErlangPid from, OtpErlangPid dest,
			OtpOutputStream payload) throws IOException {
		if (!connected) {
			throw new IOException("Not connected");
		}
		final OtpOutputStream header = new OtpOutputStream(headerLen);

		// preamble: 4 byte length + "passthrough" tag + version
		header.write4BE(0); // reserve space for length
		header.write1(passThrough);
		header.write1(version);

		// header info
		header.write_tuple_head(3);
		header.write_long(sendTag);
		if (sendCookie) {
			header.write_atom(self.cookie());
		} else {
			header.write_atom("");
		}
		header.write_any(dest);

		// version for payload
		header.write1(version);

		// fix up length in preamble
		header.poke4BE(0, header.count() + payload.count() - 4);

		do_send(header, payload);
	}

	/*
	 * Send an auth error to peer because he sent a bad cookie. The auth error
	 * uses his cookie (not revealing ours). This is just like send_reg
	 * otherwise
	 */
	@SuppressWarnings("finally")
	private void cookieError(OtpLocalNode local, OtpErlangAtom cookie)
			throws OtpAuthException {
		try {
			final OtpOutputStream header = new OtpOutputStream(headerLen);

			// preamble: 4 byte length + "passthrough" tag + version
			header.write4BE(0); // reserve space for length
			header.write1(passThrough);
			header.write1(version);

			header.write_tuple_head(4);
			header.write_long(regSendTag);
			header.write_any(local.createPid()); // disposable pid
			header.write_atom(cookie.atomValue()); // important: his cookie,
			// not mine...
			header.write_atom("auth");

			// version for payload
			header.write1(version);

			// the payload

			// the no_auth message (copied from Erlang) Don't change this
			// (Erlang will
			// crash)
			// {$gen_cast, {print, "~n** Unauthorized cookie ~w **~n",
			// [foo@aule]}}
			final OtpErlangObject[] msg = new OtpErlangObject[2];
			final OtpErlangObject[] msgbody = new OtpErlangObject[3];

			msgbody[0] = new OtpErlangAtom("print");
			msgbody[1] = new OtpErlangString("~n** Bad cookie sent to " + local
					+ " **~n");
			// Erlang will crash and burn if there is no third argument here...
			msgbody[2] = new OtpErlangList(); // empty list

			msg[0] = new OtpErlangAtom("$gen_cast");
			msg[1] = new OtpErlangTuple(msgbody);

			final OtpOutputStream payload = new OtpOutputStream(
					new OtpErlangTuple(msg));

			// fix up length in preamble
			header.poke4BE(0, header.count() + payload.count() - 4);

			try {
				do_send(header, payload);
			} catch (final IOException e) {
				// ignore
			}
		} finally {
			close();
			throw new OtpAuthException("Remote cookie not authorized: "
					+ cookie.atomValue());
		}
	}

	// link to pid

	/**
	 * Create a link between the local node and the specified process on the
	 * remote node. If the link is still active when the remote process
	 * terminates, an exit signal will be sent to this connection. Use
	 * {@link #sendUnlink unlink()} to remove the link.
	 * 
	 * @param dest
	 *            the Erlang PID of the remote process.
	 * 
	 * @exception java.io.IOException
	 *                if the connection is not active or a communication error
	 *                occurs.
	 */
	protected void sendLink(OtpErlangPid from, OtpErlangPid dest)
			throws IOException {
		if (!connected) {
			throw new IOException("Not connected");
		}
		final OtpOutputStream header = new OtpOutputStream(headerLen);

		// preamble: 4 byte length + "passthrough" tag
		header.write4BE(0); // reserve space for length
		header.write1(passThrough);
		header.write1(version);

		// header
		header.write_tuple_head(3);
		header.write_long(linkTag);
		header.write_any(from);
		header.write_any(dest);

		// fix up length in preamble
		header.poke4BE(0, header.count() - 4);

		do_send(header);
	}

	/**
	 * Remove a link between the local node and the specified process on the
	 * remote node. This method deactivates links created with
	 * {@link #sendLink link()}.
	 * 
	 * @param dest
	 *            the Erlang PID of the remote process.
	 * 
	 * @exception java.io.IOException
	 *                if the connection is not active or a communication error
	 *                occurs.
	 */
	protected void sendUnlink(OtpErlangPid from, OtpErlangPid dest)
			throws IOException {
		if (!connected) {
			throw new IOException("Not connected");
		}
		final OtpOutputStream header = new OtpOutputStream(headerLen);

		// preamble: 4 byte length + "passthrough" tag
		header.write4BE(0); // reserve space for length
		header.write1(passThrough);
		header.write1(version);

		// header
		header.write_tuple_head(3);
		header.write_long(unlinkTag);
		header.write_any(from);
		header.write_any(dest);

		// fix up length in preamble
		header.poke4BE(0, header.count() - 4);

		do_send(header);
	}

	/* used internally when "processes" terminate */
	protected void sendExit(OtpErlangPid from, OtpErlangPid dest,
			OtpErlangObject reason) throws IOException {
		sendExit(exitTag, from, dest, reason);
	}

	/**
	 * Send an exit signal to a remote process.
	 * 
	 * @param dest
	 *            the Erlang PID of the remote process.
	 * @param reason
	 *            an Erlang term describing the exit reason.
	 * 
	 * @exception java.io.IOException
	 *                if the connection is not active or a communication error
	 *                occurs.
	 */
	protected void sendExit2(OtpErlangPid from, OtpErlangPid dest,
			OtpErlangObject reason) throws IOException {
		sendExit(exit2Tag, from, dest, reason);
	}

	private void sendExit(int tag, OtpErlangPid from, OtpErlangPid dest,
			OtpErlangObject reason) throws IOException {
		if (!connected) {
			throw new IOException("Not connected");
		}
		final OtpOutputStream header = new OtpOutputStream(headerLen);

		// preamble: 4 byte length + "passthrough" tag
		header.write4BE(0); // reserve space for length
		header.write1(passThrough);
		header.write1(version);

		// header
		header.write_tuple_head(4);
		header.write_long(tag);
		header.write_any(from);
		header.write_any(dest);
		header.write_any(reason);

		// fix up length in preamble
		header.poke4BE(0, header.count() - 4);

		do_send(header);
	}

	@Override
	public void run() {
		if (!connected) {
			deliver(new IOException("Not connected"));
			return;
		}

		final byte[] lbuf = new byte[4];
		OtpInputStream ibuf;
		OtpErlangObject traceobj;
		int len;
		final byte[] tock = { 0, 0, 0, 0 };

		try {
			receive_loop: while (!done) {
				// don't return until we get a real message
				// or a failure of some kind (e.g. EXIT)
				// read length and read buffer must be atomic!
				do {
					// read 4 bytes - get length of incoming packet
					// socket.getInputStream().read(lbuf);
					readSock(socket, lbuf);
					ibuf = new OtpInputStream(lbuf);
					len = ibuf.read4BE();

					// received tick? send tock!
					if (len == 0) {
						synchronized (this) {
							socket.getOutputStream().write(tock);
						}
					}

				} while (len == 0); // tick_loop

				// got a real message (maybe) - read len bytes
				final byte[] tmpbuf = new byte[len];
				// i = socket.getInputStream().read(tmpbuf);
				readSock(socket, tmpbuf);
				ibuf = new OtpInputStream(tmpbuf);

				if (ibuf.read1() != passThrough) {
					break receive_loop;
				}

				// got a real message (really)
				OtpErlangObject reason = null;
				OtpErlangAtom cookie = null;
				OtpErlangObject tmp = null;
				OtpErlangTuple head = null;
				OtpErlangAtom toName;
				OtpErlangPid to;
				OtpErlangPid from;
				int tag;

				// decode the header
				tmp = ibuf.read_any();
				if (!(tmp instanceof OtpErlangTuple)) {
					break receive_loop;
				}

				head = (OtpErlangTuple) tmp;
				if (!(head.elementAt(0) instanceof OtpErlangLong)) {
					break receive_loop;
				}

				// lets see what kind of message this is
				tag = (int) ((OtpErlangLong) (head.elementAt(0))).longValue();

				switch (tag) {
				case sendTag: // { SEND, Cookie, ToPid }
				case sendTTTag: // { SEND, Cookie, ToPid, TraceToken }
					if (!cookieOk) {
						// we only check this once, he can send us bad cookies
						// later if he
						// likes
						if (!(head.elementAt(1) instanceof OtpErlangAtom)) {
							break receive_loop;
						}
						cookie = (OtpErlangAtom) head.elementAt(1);
						if (sendCookie) {
							if (!cookie.atomValue().equals(self.cookie())) {
								cookieError(self, cookie);
							}
						} else {
							if (!cookie.atomValue().equals("")) {
								cookieError(self, cookie);
							}
						}
						cookieOk = true;
					}

					if (traceLevel >= sendThreshold) {
						System.out.println("<- " + headerType(head) + " "
								+ head);

						/* show received payload too */
						ibuf.mark(0);
						traceobj = ibuf.read_any();

						if (traceobj != null) {
							System.out.println("   " + traceobj);
						} else {
							System.out.println("   (null)");
						}
						ibuf.reset();
					}

					to = (OtpErlangPid) (head.elementAt(2));

					deliver(new OtpMsg(to, ibuf));
					break;

				case regSendTag: // { REG_SEND, FromPid, Cookie, ToName }
				case regSendTTTag: // { REG_SEND, FromPid, Cookie, ToName,
					// TraceToken }
					if (!cookieOk) {
						// we only check this once, he can send us bad cookies
						// later if he
						// likes
						if (!(head.elementAt(2) instanceof OtpErlangAtom)) {
							break receive_loop;
						}
						cookie = (OtpErlangAtom) head.elementAt(2);
						if (sendCookie) {
							if (!cookie.atomValue().equals(self.cookie())) {
								cookieError(self, cookie);
							}
						} else {
							if (!cookie.atomValue().equals("")) {
								cookieError(self, cookie);
							}
						}
						cookieOk = true;
					}

					if (traceLevel >= sendThreshold) {
						System.out.println("<- " + headerType(head) + " "
								+ head);

						/* show received payload too */
						ibuf.mark(0);
						traceobj = ibuf.read_any();

						if (traceobj != null) {
							System.out.println("   " + traceobj);
						} else {
							System.out.println("   (null)");
						}
						ibuf.reset();
					}

					from = (OtpErlangPid) (head.elementAt(1));
					toName = (OtpErlangAtom) (head.elementAt(3));

					deliver(new OtpMsg(from, toName.atomValue(), ibuf));
					break;

				case exitTag: // { EXIT, FromPid, ToPid, Reason }
				case exit2Tag: // { EXIT2, FromPid, ToPid, Reason }
					if (head.elementAt(3) == null) {
						break receive_loop;
					}
					if (traceLevel >= ctrlThreshold) {
						System.out.println("<- " + headerType(head) + " "
								+ head);
					}

					from = (OtpErlangPid) (head.elementAt(1));
					to = (OtpErlangPid) (head.elementAt(2));
					reason = head.elementAt(3);

					deliver(new OtpMsg(tag, from, to, reason));
					break;

				case exitTTTag: // { EXIT, FromPid, ToPid, TraceToken, Reason }
				case exit2TTTag: // { EXIT2, FromPid, ToPid, TraceToken,
					// Reason }
					// as above, but bifferent element number
					if (head.elementAt(4) == null) {
						break receive_loop;
					}
					if (traceLevel >= ctrlThreshold) {
						System.out.println("<- " + headerType(head) + " "
								+ head);
					}

					from = (OtpErlangPid) (head.elementAt(1));
					to = (OtpErlangPid) (head.elementAt(2));
					reason = head.elementAt(4);

					deliver(new OtpMsg(tag, from, to, reason));
					break;

				case linkTag: // { LINK, FromPid, ToPid}
				case unlinkTag: // { UNLINK, FromPid, ToPid}
					if (traceLevel >= ctrlThreshold) {
						System.out.println("<- " + headerType(head) + " "
								+ head);
					}

					from = (OtpErlangPid) (head.elementAt(1));
					to = (OtpErlangPid) (head.elementAt(2));

					deliver(new OtpMsg(tag, from, to));
					break;

				// absolutely no idea what to do with these, so we ignore
				// them...
				case groupLeaderTag: // { GROUPLEADER, FromPid, ToPid}
				case nodeLinkTag: // { NODELINK }
					// (just show trace)
					if (traceLevel >= ctrlThreshold) {
						System.out.println("<- " + headerType(head) + " "
								+ head);
					}
					break;

				default:
					// garbage?
					break receive_loop;
				}
			} // end receive_loop

			// this section reachable only with break
			// we have received garbage from peer
			deliver(new OtpErlangExit("Remote is sending garbage"));

		} // try

		catch (final OtpAuthException e) {
			deliver(e);
		} catch (final OtpErlangDecodeException e) {
			deliver(new OtpErlangExit("Remote is sending garbage"));
		} catch (final IOException e) {
			deliver(new OtpErlangExit("Remote has closed connection"));
		} catch (final OtpErlangRangeException e) {
			deliver(new OtpErlangExit("Remote is sending garbage"));
		} finally {
			close();
		}
	}

	/**
	 * <p>
	 * Set the trace level for this connection. Normally tracing is off by
	 * default unless System property OtpConnection.trace was set.
	 * </p>
	 * 
	 * <p>
	 * The following levels are valid: 0 turns off tracing completely, 1 shows
	 * ordinary send and receive messages, 2 shows control messages such as link
	 * and unlink, 3 shows handshaking at connection setup, and 4 shows
	 * communication with Epmd. Each level includes the information shown by the
	 * lower ones.
	 * </p>
	 * 
	 * @param level
	 *            the level to set.
	 * 
	 * @return the previous trace level.
	 */
	public int setTraceLevel(int level) {
		final int oldLevel = traceLevel;

		// pin the value
		if (level < 0) {
			level = 0;
		} else if (level > 4) {
			level = 4;
		}

		traceLevel = level;

		return oldLevel;
	}

	/**
	 * Get the trace level for this connection.
	 * 
	 * @return the current trace level.
	 */
	public int getTraceLevel() {
		return traceLevel;
	}

	/**
	 * Close the connection to the remote node.
	 */
	public void close() {
		done = true;
		connected = false;
		synchronized (this) {
			try {
				if (socket != null) {
					if (traceLevel >= ctrlThreshold) {
						System.out.println("-> CLOSE");
					}
					socket.close();
				}
			} catch (final IOException e) { /* ignore socket close errors */
			} finally {
				socket = null;
			}
		}
	}

	@Override
	protected void finalize() {
		close();
	}

	/**
	 * Determine if the connection is still alive. Note that this method only
	 * reports the status of the connection, and that it is possible that there
	 * are unread messages waiting in the receive queue.
	 * 
	 * @return true if the connection is alive.
	 */
	public boolean isConnected() {
		return connected;
	}

	// used by send and send_reg (message types with payload)
	protected synchronized void do_send(OtpOutputStream header,
			OtpOutputStream payload) throws IOException {
		try {
			if (traceLevel >= sendThreshold) {
				// Need to decode header and output buffer to show trace
				// message!
				// First make OtpInputStream, then decode.
				try {
					final OtpErlangObject h = (header.getOtpInputStream(5))
							.read_any();
					System.out.println("-> " + headerType(h) + " " + h);

					OtpErlangObject o = (payload.getOtpInputStream(0))
							.read_any();
					System.out.println("   " + o);
					o = null;
				} catch (final OtpErlangDecodeException e) {
					System.out.println("   " + "can't decode output buffer:"
							+ e);
				} catch (final OtpErlangRangeException e) {
					System.out.println("   " + "can't decode output buffer: "
							+ e);
				}
			}

			header.writeTo(socket.getOutputStream());
			payload.writeTo(socket.getOutputStream());
		} catch (final IOException e) {
			close();
			throw e;
		}
	}

	// used by the other message types
	protected synchronized void do_send(OtpOutputStream header)
			throws IOException {
		try {
			if (traceLevel >= ctrlThreshold) {
				try {
					final OtpErlangObject h = (header.getOtpInputStream(5))
							.read_any();
					System.out.println("-> " + headerType(h) + " " + h);
				} catch (final OtpErlangDecodeException e) {
					System.out.println("   " + "can't decode output buffer: "
							+ e);
				} catch (final OtpErlangRangeException e) {
					System.out.println("   " + "can't decode output buffer: "
							+ e);
				}
			}
			header.writeTo(socket.getOutputStream());
		} catch (final IOException e) {
			close();
			throw e;
		}
	}

	protected String headerType(OtpErlangObject h)
			throws OtpErlangRangeException {
		int tag = -1;

		if (h instanceof OtpErlangTuple) {
			tag = (int) (((OtpErlangLong) (((OtpErlangTuple) h).elementAt(0)))
					.longValue());
		}

		switch (tag) {
		case linkTag:
			return "LINK";

		case sendTag:
			return "SEND";

		case exitTag:
			return "EXIT";

		case unlinkTag:
			return "UNLINK";

		case nodeLinkTag:
			return "NODELINK";

		case regSendTag:
			return "REG_SEND";

		case groupLeaderTag:
			return "GROUP_LEADER";

		case exit2Tag:
			return "EXIT2";

		case sendTTTag:
			return "SEND_TT";

		case exitTTTag:
			return "EXIT_TT";

		case regSendTTTag:
			return "REG_SEND_TT";

		case exit2TTTag:
			return "EXIT2_TT";
		}

		return "(unknown type)";
	}

	/* this method now throws exception if we don't get full read */
	protected int readSock(Socket s, byte[] b) throws IOException {
		int got = 0;
		final int len = b.length;
		int i;
		InputStream is = null;

		synchronized (this) {
			if (s == null) {
				throw new IOException("expected " + len
						+ " bytes, socket was closed");
			}
			is = s.getInputStream();
		}

		while (got < len) {
			i = is.read(b, got, len - got);

			if (i < 0) {
				throw new IOException("expected " + len
						+ " bytes, got EOF after " + got + " bytes");
			} else {
				got += i;
			}
		}
		return got;
	}

	protected void doAccept() throws IOException, OtpAuthException {
		try {
			sendStatus("ok");
			final int our_challenge = genChallenge();
			sendChallenge(peer.distChoose, self.flags, our_challenge);
			final int her_challenge = recvChallengeReply(our_challenge);
			final byte[] our_digest = genDigest(her_challenge, self.cookie());
			sendChallengeAck(our_digest);
			connected = true;
			cookieOk = true;
			sendCookie = false;
		} catch (final IOException ie) {
			close();
			throw ie;
		} catch (final OtpAuthException ae) {
			close();
			throw ae;
		} catch (final Exception e) {
			final String nn = peer.node();
			close();
			throw new IOException("Error accepting connection from " + nn);
		}
		if (traceLevel >= handshakeThreshold) {
			System.out.println("<- MD5 ACCEPTED " + peer.host());
		}
	}

	protected void doConnect(int port) throws IOException, OtpAuthException {
		try {
			socket = new Socket(peer.host(), port);
			socket.setTcpNoDelay(true);

			if (traceLevel >= handshakeThreshold) {
				System.out.println("-> MD5 CONNECT TO " + peer.host() + ":"
						+ port);
			}
			sendName(peer.distChoose, self.flags);
			recvStatus();
			final int her_challenge = recvChallenge();
			final byte[] our_digest = genDigest(her_challenge, self.cookie());
			final int our_challenge = genChallenge();
			sendChallengeReply(our_challenge, our_digest);
			recvChallengeAck(our_challenge);
			cookieOk = true;
			sendCookie = false;
		} catch (final OtpAuthException ae) {
			close();
			throw ae;
		} catch (final Exception e) {
			close();
			throw new IOException("Cannot connect to peer node");
		}
	}

	// This is nooo good as a challenge,
	// XXX fix me.
	static protected int genChallenge() {
		return random.nextInt();
	}

	// Used to debug print a message digest
	static String hex0(byte x) {
		final char tab[] = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
				'a', 'b', 'c', 'd', 'e', 'f' };
		int uint;
		if (x < 0) {
			uint = x & 0x7F;
			uint |= (1 << 7);
		} else {
			uint = x;
		}
		return "" + tab[uint >>> 4] + tab[uint & 0xF];
	}

	static String hex(byte[] b) {
		final StringBuffer sb = new StringBuffer();
		try {
			int i;
			for (i = 0; i < b.length; ++i) {
				sb.append(hex0(b[i]));
			}
		} catch (final Exception e) {
			// Debug function, ignore errors.
		}
		return sb.toString();

	}

	protected byte[] genDigest(int challenge, String cookie) {
		int i;
		long ch2;

		if (challenge < 0) {
			ch2 = 1L << 31;
			ch2 |= (challenge & 0x7FFFFFFF);
		} else {
			ch2 = challenge;
		}
		final OtpMD5 context = new OtpMD5();
		context.update(cookie);
		context.update("" + ch2);

		final int[] tmp = context.final_bytes();
		final byte[] res = new byte[tmp.length];
		for (i = 0; i < tmp.length; ++i) {
			res[i] = (byte) (tmp[i] & 0xFF);
		}
		return res;
	}

	protected void sendName(int dist, int flags) throws IOException {

		final OtpOutputStream obuf = new OtpOutputStream();
		final String str = self.node();
		obuf.write2BE(str.length() + 7); // 7 bytes + nodename
		obuf.write1(AbstractNode.NTYPE_R6);
		obuf.write2BE(dist);
		obuf.write4BE(flags);
		obuf.write(str.getBytes());

		obuf.writeTo(socket.getOutputStream());

		if (traceLevel >= handshakeThreshold) {
			System.out.println("-> " + "HANDSHAKE sendName" + " flags=" + flags
					+ " dist=" + dist + " local=" + self);
		}
	}

	protected void sendChallenge(int dist, int flags, int challenge)
			throws IOException {

		final OtpOutputStream obuf = new OtpOutputStream();
		final String str = self.node();
		obuf.write2BE(str.length() + 11); // 11 bytes + nodename
		obuf.write1(AbstractNode.NTYPE_R6);
		obuf.write2BE(dist);
		obuf.write4BE(flags);
		obuf.write4BE(challenge);
		obuf.write(str.getBytes());

		obuf.writeTo(socket.getOutputStream());

		if (traceLevel >= handshakeThreshold) {
			System.out.println("-> " + "HANDSHAKE sendChallenge" + " flags="
					+ flags + " dist=" + dist + " challenge=" + challenge
					+ " local=" + self);
		}
	}

	protected byte[] read2BytePackage() throws IOException,
			OtpErlangDecodeException {

		final byte[] lbuf = new byte[2];
		byte[] tmpbuf;

		readSock(socket, lbuf);
		final OtpInputStream ibuf = new OtpInputStream(lbuf);
		final int len = ibuf.read2BE();
		tmpbuf = new byte[len];
		readSock(socket, tmpbuf);
		return tmpbuf;
	}

	protected void recvName(OtpPeer peer_) throws IOException {

		String hisname = "";

		try {
			final byte[] tmpbuf = read2BytePackage();
			final OtpInputStream ibuf = new OtpInputStream(tmpbuf);
			byte[] tmpname;
			final int len = tmpbuf.length;
			peer_.ntype = ibuf.read1();
			if (peer_.ntype != AbstractNode.NTYPE_R6) {
				throw new IOException("Unknown remote node type");
			}
			peer_.distLow = peer_.distHigh = ibuf.read2BE();
			if (peer_.distLow < 5) {
				throw new IOException("Unknown remote node type");
			}
			peer_.flags = ibuf.read4BE();
			tmpname = new byte[len - 7];
			ibuf.readN(tmpname);
			hisname = new String(tmpname);
			// Set the old nodetype parameter to indicate hidden/normal status
			// When the old handshake is removed, the ntype should also be.
			if ((peer_.flags & AbstractNode.dFlagPublished) != 0) {
				peer_.ntype = AbstractNode.NTYPE_R4_ERLANG;
			} else {
				peer_.ntype = AbstractNode.NTYPE_R4_HIDDEN;
			}

			if ((peer_.flags & AbstractNode.dFlagExtendedReferences) == 0) {
				throw new IOException(
						"Handshake failed - peer cannot handle extended references");
			}

			if (OtpSystem.useExtendedPidsPorts()
					&& (peer_.flags & AbstractNode.dFlagExtendedPidsPorts) == 0) {
				throw new IOException(
						"Handshake failed - peer cannot handle extended pids and ports");
			}

		} catch (final OtpErlangDecodeException e) {
			throw new IOException("Handshake failed - not enough data");
		}

		final int i = hisname.indexOf('@', 0);
		peer_.node = hisname;
		peer_.alive = hisname.substring(0, i);
		peer_.host = hisname.substring(i + 1, hisname.length());

		if (traceLevel >= handshakeThreshold) {
			System.out.println("<- " + "HANDSHAKE" + " ntype=" + peer_.ntype
					+ " dist=" + peer_.distHigh + " remote=" + peer_);
		}
	}

	protected int recvChallenge() throws IOException {

		int challenge;

		try {
			final byte[] buf = read2BytePackage();
			final OtpInputStream ibuf = new OtpInputStream(buf);
			peer.ntype = ibuf.read1();
			if (peer.ntype != AbstractNode.NTYPE_R6) {
				throw new IOException("Unexpected peer type");
			}
			peer.distLow = peer.distHigh = ibuf.read2BE();
			peer.flags = ibuf.read4BE();
			challenge = ibuf.read4BE();
			final byte[] tmpname = new byte[buf.length - 11];
			ibuf.readN(tmpname);
			final String hisname = new String(tmpname);
			final int i = hisname.indexOf('@', 0);
			peer.node = hisname;
			peer.alive = hisname.substring(0, i);
			peer.host = hisname.substring(i + 1, hisname.length());

			if ((peer.flags & AbstractNode.dFlagExtendedReferences) == 0) {
				throw new IOException(
						"Handshake failed - peer cannot handle extended references");
			}

			if (OtpSystem.useExtendedPidsPorts()
					&& (peer.flags & AbstractNode.dFlagExtendedPidsPorts) == 0) {
				throw new IOException(
						"Handshake failed - peer cannot handle extended pids and ports");
			}

		} catch (final OtpErlangDecodeException e) {
			throw new IOException("Handshake failed - not enough data");
		}

		if (traceLevel >= handshakeThreshold) {
			System.out.println("<- " + "HANDSHAKE recvChallenge" + " from="
					+ peer.node + " challenge=" + challenge + " local=" + self);
		}

		return challenge;
	}

	protected void sendChallengeReply(int challenge, byte[] digest)
			throws IOException {

		final OtpOutputStream obuf = new OtpOutputStream();
		obuf.write2BE(21);
		obuf.write1(ChallengeReply);
		obuf.write4BE(challenge);
		obuf.write(digest);
		obuf.writeTo(socket.getOutputStream());

		if (traceLevel >= handshakeThreshold) {
			System.out.println("-> " + "HANDSHAKE sendChallengeReply"
					+ " challenge=" + challenge + " digest=" + hex(digest)
					+ " local=" + self);
		}
	}

	// Would use Array.equals in newer JDK...
	private boolean digests_equals(byte[] a, byte[] b) {
		int i;
		for (i = 0; i < 16; ++i) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}

	protected int recvChallengeReply(int our_challenge) throws IOException,
			OtpAuthException {

		int challenge;
		final byte[] her_digest = new byte[16];

		try {
			final byte[] buf = read2BytePackage();
			final OtpInputStream ibuf = new OtpInputStream(buf);
			final int tag = ibuf.read1();
			if (tag != ChallengeReply) {
				throw new IOException("Handshake protocol error");
			}
			challenge = ibuf.read4BE();
			ibuf.readN(her_digest);
			final byte[] our_digest = genDigest(our_challenge, self.cookie());
			if (!digests_equals(her_digest, our_digest)) {
				throw new OtpAuthException("Peer authentication error.");
			}
		} catch (final OtpErlangDecodeException e) {
			throw new IOException("Handshake failed - not enough data");
		}

		if (traceLevel >= handshakeThreshold) {
			System.out.println("<- " + "HANDSHAKE recvChallengeReply"
					+ " from=" + peer.node + " challenge=" + challenge
					+ " digest=" + hex(her_digest) + " local=" + self);
		}

		return challenge;
	}

	protected void sendChallengeAck(byte[] digest) throws IOException {

		final OtpOutputStream obuf = new OtpOutputStream();
		obuf.write2BE(17);
		obuf.write1(ChallengeAck);
		obuf.write(digest);

		obuf.writeTo(socket.getOutputStream());

		if (traceLevel >= handshakeThreshold) {
			System.out.println("-> " + "HANDSHAKE sendChallengeAck"
					+ " digest=" + hex(digest) + " local=" + self);
		}
	}

	protected void recvChallengeAck(int our_challenge) throws IOException,
			OtpAuthException {

		final byte[] her_digest = new byte[16];
		try {
			final byte[] buf = read2BytePackage();
			final OtpInputStream ibuf = new OtpInputStream(buf);
			final int tag = ibuf.read1();
			if (tag != ChallengeAck) {
				throw new IOException("Handshake protocol error");
			}
			ibuf.readN(her_digest);
			final byte[] our_digest = genDigest(our_challenge, self.cookie());
			if (!digests_equals(her_digest, our_digest)) {
				throw new OtpAuthException("Peer authentication error.");
			}
		} catch (final OtpErlangDecodeException e) {
			throw new IOException("Handshake failed - not enough data");
		} catch (final Exception e) {
			throw new OtpAuthException("Peer authentication error.");
		}

		if (traceLevel >= handshakeThreshold) {
			System.out.println("<- " + "HANDSHAKE recvChallengeAck" + " from="
					+ peer.node + " digest=" + hex(her_digest) + " local="
					+ self);
		}
	}

	protected void sendStatus(String status) throws IOException {

		final OtpOutputStream obuf = new OtpOutputStream();
		obuf.write2BE(status.length() + 1);
		obuf.write1(ChallengeStatus);
		obuf.write(status.getBytes());

		obuf.writeTo(socket.getOutputStream());

		if (traceLevel >= handshakeThreshold) {
			System.out.println("-> " + "HANDSHAKE sendStatus" + " status="
					+ status + " local=" + self);
		}
	}

	protected void recvStatus() throws IOException {

		try {
			final byte[] buf = read2BytePackage();
			final OtpInputStream ibuf = new OtpInputStream(buf);
			final int tag = ibuf.read1();
			if (tag != ChallengeStatus) {
				throw new IOException("Handshake protocol error");
			}
			final byte[] tmpbuf = new byte[buf.length - 1];
			ibuf.readN(tmpbuf);
			final String status = new String(tmpbuf);

			if (status.compareTo("ok") != 0) {
				throw new IOException("Peer replied with status '" + status
						+ "' instead of 'ok'");
			}
		} catch (final OtpErlangDecodeException e) {
			throw new IOException("Handshake failed - not enough data");
		}
		if (traceLevel >= handshakeThreshold) {
			System.out.println("<- " + "HANDSHAKE recvStatus (ok)" + " local="
					+ self);
		}
	}
}
