/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.jss.tests;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;

import org.mozilla.jss.CryptoManager;
import org.mozilla.jss.NotInitializedException;
import org.mozilla.jss.crypto.CryptoToken;
import org.mozilla.jss.crypto.TokenException;
import org.mozilla.jss.crypto.X509Certificate;
import org.mozilla.jss.ssl.SSLHandshakeCompletedEvent;
import org.mozilla.jss.ssl.SSLHandshakeCompletedListener;
import org.mozilla.jss.ssl.SSLSecurityStatus;
import org.mozilla.jss.ssl.SSLSocket;
import org.mozilla.jss.util.IncorrectPasswordException;
import org.mozilla.jss.util.PasswordCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**************
 * Note on how to use JSS_SelfServServer and JSS_SelfServerClient
 *
 * For debugging purposes you should modify Constant.java debug_level to 4.
 *
 * First create db's and certificates
 * java -cp jss4.jar org.mozilla.jss.tests.SetupDBs . ./passwords
 * java -cp jss4.jar org.mozilla.jss.tests.GenerateTestCert . /passwords
 *                             localhost SHA-256/RSA CA_RSA Client_RSA Server_RSA
 *
 * Start the server:
 *
 *  java -cp ./jss4.jar org.mozilla.jss.tests.JSS_SelfServServer . passwords
 *             localhost false 2921 verboseoff
 *
 * Start the client with 4 threads using ciphersuite 0x33.
 * Look at the file Constant.java for the ciphersuites values.
 *
 * java -cp jss4.jar org.mozilla.jss.tests.JSS_SelfServClient 2 0x33
 * . localhost 2921 verboseoff JSS Client_RSA
 *
 * If you envoke the client with a ciphersuite value -1
 * then all current JSS ciphersuites will be tested fox X number of
 * threads, and once all ciphersuites have been tested the client
 * will closed all client SSLSockets and then tell the server to
 * shutdown. This case is for the nightly automated tests.
 *
 * java -cp jss4.jar org.mozilla.jss.tests.JSS_SelfServClient 4 -1
 * . passwords localhost 2921 verboseoff JSS
 */

interface ConstantsBase {
    // Test all implemented ciphersuites
    public static final int TEST_CIPHERS = -1;

}



public class JSS_SelfServClient implements ConstantsBase, Constants {

    public static Logger logger = LoggerFactory.getLogger(JSS_SelfServClient.class);

    private String  clientCertNick       = "default";
    private String  serverHost           = "localhost";
    private String  ciphersuiteTested    = null;
    private boolean TestCertCallBack     = false;
    private boolean success              = true;
    private int     fCipher              = TEST_CIPHERS;
    private int     aWorkingCipher       = 0;
    private boolean bTestCiphers         = true;
    private String  CipherName           = null;
    private int     port                 = 29754;
    private String  EOF                  = "test";
    private boolean handshakeCompleted   = false;
    private boolean bVerbose             = false;
    private boolean bFipsMode            = false;

    /* ciphersuites to test */
    private ArrayList<Integer> ciphersToTest      = new ArrayList<>();

    private CryptoManager    cm          = null;
    private CryptoToken      tok         = null;
    private PasswordCallback cb          = null;
    private String  fPasswordFile        = "passwords";
    private String  fCertDbPath          = ".";
    private ArrayList<SSLSocket> sockList           = new ArrayList<>();

    /* h_ciphers is for ciphersuite that were able to successfully
     * connect to the server */
    private ArrayList<String> h_ciphers          = new ArrayList<>();

    /* f_ciphers is for ciphersuite that failed to connect to the server */
    private ArrayList<String> f_ciphers          = new ArrayList<>();

    private int sockID                   = 0;
    /* JSS only needs to be initailized for one instance */
    private static boolean bJSS          = false;
    private ThreadGroup socketThreads    = new ThreadGroup("SSLSockets");

    public void setTestCiphers(boolean t) {
        bTestCiphers = t;
    }

    public boolean getTestCiphers() {
        return bTestCiphers;
    }
    /**
     * Default Constructor.
     */
    public JSS_SelfServClient() {
        if (logger.isDebugEnabled()) {
            bVerbose = true;
        }
        /*
         *if (bVerbose) {
         *    for (int i = 0; i < jssCipherSuites.length; i++) {
         *        System.out.println(jssCipherSuites[i].name + " " +
         *            Integer.toHexString(jssCipherSuites[i].value) );
         *    }
         *}
         */

    }

    public void setVerbose(boolean v) {
        bVerbose = v;
    }

    /**
     * returns true if JSS is sync with NSS ciphersuites.
     */
    public boolean testJSSCiphersMatchNSS() {

        initJSS();
        boolean cipherSuites = true;
        int ciphers[] =
                org.mozilla.jss.ssl.SSLSocket.getImplementedCipherSuites();

        for (int i = 0; i < ciphers.length; i++) {
            //if we do not find the ciphersuite than the JSS
            // table is out of date.
            if (Constants.cipher.cipherToString(ciphers[i]) == null) {
                cipherSuites = false;
                System.out.println("JSS does not support ciphersuite: " +
                        Integer.toHexString(ciphers[i]));
            }
        }

        if (!cipherSuites) {
            System.out.println("ERROR: NSS has implemented " +
                    "ciphersuites that JSS does not support!\n");
            System.out.println("see http://mxr.mozilla.org/nss/" +
                    "source/lib/ssl/sslproto.h");
            System.out.println("Update org/mozilla/jss/ssl/" +
                    "SSLSocket.java");
            System.out.println("Update org/mozilla/jss/tests/" +
                    "Constants.java");

            System.out.println("NSS implemented ciphersuites " +
                    "missing from JSS");
        }
        return cipherSuites;
    }

    public void configureDefaultSSLOptions() {
        initJSS();
        try {
            //Disable SSL2
            SSLSocket.enableSSL2Default(false);

            //if in FIPS mode disable SSL3
            if (bFipsMode)
                SSLSocket.enableSSL3Default(false);

        } catch (SocketException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    public void configureCipherSuites(String server) {
        int ciphers[] =
                org.mozilla.jss.ssl.SSLSocket.getImplementedCipherSuites();
        boolean testCipher;

        for (int i = 0; i < ciphers.length;  ++i) {
            String ciphersuite = Constants.cipher.cipherToString(ciphers[i]);
            testCipher = true;
            if (bVerbose) {
                System.out.print(ciphersuite);
            }
            // This class uses the TLS versions that NSS enables by default.
            // Until NSS enables TLS 1.2 by default, don't test the cipher
            // suites that only work in TLS 1.2.
            if ((ciphersuite.indexOf("_SHA256") != -1) ||
                    (ciphersuite.indexOf("_SHA384") != -1)  ) {
                if (bVerbose) System.out.print(" -");
                testCipher = false;
            }
            if (server.equalsIgnoreCase("JSS")) {
                //For JSS SSLServer don't test

                if ((ciphersuite.indexOf("_DHE_") != -1) ||
                        (ciphersuite.indexOf("SSL2") != -1) ||
                        //Need to figure out why _ECDH_RSA don't work
                        ( (ciphersuite.indexOf("RSA") != -1) &&
                        (ciphersuite.indexOf("_ECDH_") != -1)) ) {
                    if (bVerbose) System.out.print(" -");
                    testCipher = false;
                }
            }
            if (server.equalsIgnoreCase("JSSE")) {
                //For JSSE SSLServers don't test
                if ((ciphersuite.indexOf("SSL2_") != -1)    ||
                        (ciphersuite.indexOf("_ECDHE_") != -1)  ||
                        (ciphersuite.indexOf("_ECDH_") != -1)   ||
                        (ciphersuite.indexOf("_CAMELLIA_") != -1) ||
                        (ciphersuite.indexOf("_SEED_") != -1) ||
                        (ciphersuite.indexOf("_DHE_DSS_") != -1) ||
                        (ciphersuite.indexOf("_EXPORT1024_") != -1) ||
                        (ciphersuite.indexOf("_RSA_FIPS_") != -1)  ||
                        (ciphersuite.indexOf("EXPORT_WITH_RC2") != -1) ||
                        (ciphersuite.indexOf("_ECDSA_") != -1) ||
                        (ciphersuite.indexOf("_256_") != -1)  ) {
                    if (bVerbose) System.out.print(" -");
                    testCipher = false;
                }
            }
            if (server.equalsIgnoreCase("Mozilla-JSS")) {
                //For JSSE Mozilla-JSS SSLServers don't test
                if ((ciphersuite.indexOf("SSL2_")  != -1)  ||
                        (ciphersuite.indexOf("_ECDHE_") != -1)  ||
                        (ciphersuite.indexOf("_ECDH_") != -1)   ||
                        (ciphersuite.indexOf("_CAMELLIA_") != -1) ||
                        (ciphersuite.indexOf("_SEED_") != -1) ||
                        (ciphersuite.indexOf("_DHE_DSS_") != -1)||
                        (ciphersuite.indexOf("_EXPORT1024_") != -1) ||
                        (ciphersuite.indexOf("_RSA_FIPS_") != -1)  ||
                        (ciphersuite.indexOf("EXPORT_WITH_RC2") != -1) ||
                        (ciphersuite.indexOf("_ECDSA_") != -1) ||
                        (ciphersuite.indexOf(
                        "SSL3_DHE_RSA_WITH_3DES_EDE_CBC_SHA") != -1) ||
                        (ciphersuite.indexOf(
                        "SSL3_RSA_WITH_3DES_EDE_CBC_SHA") != -1) ||
                        (ciphersuite.indexOf(
                        "SSL3_DHE_RSA_WITH_DES_CBC_SHA") != -1)||
                        (ciphersuite.indexOf(
                        "SSL3_RSA_WITH_DES_CBC_SHA") != -1) ||
                        (ciphersuite.indexOf(
                        "SSL3_RSA_EXPORT_WITH_RC4_40_MD5") != -1) ||
                        (ciphersuite.indexOf("_256_") != -1)  ) {
                    if (bVerbose) System.out.print(" -");
                    testCipher = false;
                }
            }

            if (testCipher) {
                if (bFipsMode) {
                    try {
                        if (SSLSocket.isFipsCipherSuite(ciphers[i])) {
                            ciphersToTest.add(new Integer(ciphers[i]));
                            if (bVerbose)
                                System.out.print(" - FIPS Testing");
                        } else if (bVerbose) System.out.print(" -");
                    } catch (SocketException ex) {
                        ex.printStackTrace();
                    }
                } else {
                    ciphersToTest.add(new Integer(ciphers[i]));
                    if (bVerbose) System.out.print(" - Testing");
                }
            }
        }

        if (bVerbose) System.out.print("\n");

        if(bVerbose) System.out.println("\nTesting " + ciphersToTest.size() +
                " ciphersuites.");

    }
    /**
     *For every enabled ciphersuite created numOfThreads connections.
     */
    public void testCiphersuites(int numOfThreads) {
        Iterator<Integer> iter = ciphersToTest.iterator();
        setTestCiphers(true);
        while (iter.hasNext()) {
            setCipher(iter.next().intValue());
            try {
                createSSLConnections(numOfThreads);
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        }
    }
    /**
     * Initialize the desired ciphersuite to be set
     * on the socket.
     * @param aCipher
     */
    public void setCipher(int aCipher) {

        initJSS();
        int ciphers[] =
                org.mozilla.jss.ssl.SSLSocket.getImplementedCipherSuites();

        ciphersuiteTested = Constants.cipher.cipherToString(aCipher);

        if (bVerbose || !bTestCiphers) {
            System.out.println("Testing " + Integer.toHexString(aCipher) +
                    " " + ciphersuiteTested);
        }

        if (ciphersuiteTested != null) {
            fCipher = aCipher;
        } else {
            System.out.print("ciphersuite not supported");
            System.exit(1);
        }

        try {
            if (cm.FIPSEnabled() && !SSLSocket.isFipsCipherSuite(aCipher)) {
                System.out.println("You are trying to test a non FIPS " +
                        "ciphersuite when FIPS is enabled!");
                System.exit(1);
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }

        //Disable all Ciphers we only want the one cipher
        //to be turned on
        for (int i = 0; i < ciphers.length; i++) {

            try {
                if (SSLSocket.getCipherPreferenceDefault(ciphers[i])) {
                    // System.out.println("Implemented Cipher Suite: " +
                    //     Integer.toHexString(ciphers[i]) + " is ON");
                    SSLSocket.setCipherPreferenceDefault(ciphers[i], false);
                }
            } catch (SocketException ex) {
                ex.printStackTrace();
            }
        }
        //note all ciphers are disabled but the ciphersuite to be tested
        //will only be enabled in the method createSSLSocket
    }

    /**
     * Initialize the hostname to run the server
     * @param aHostName
     */
    public void setHostName(String aHostName) {
        serverHost = aHostName;
    }

    /**
     * Initialize the port to run the server
     * @param aPort
     */
    public void setPort(int aPort) {
        port = aPort;
    }

    /**
     * Initialize the passwords file name
     * @param aPasswordFile
     */
    public void setPasswordFile(String aPasswordFile) {
        fPasswordFile = aPasswordFile;
    }

    /**
     * Initialize the cert db path name
     * @param aCertDbPath
     */
    public void setCertDbPath(String aCertDbPath) {
        fCertDbPath = aCertDbPath;
    }

    /**
     * Enable/disable Test Cert Callback.
     * @param aTestCertCallback
     */
    public void setTestCertCallback(boolean aTestCertCallback) {
        TestCertCallBack = aTestCertCallback;
    }

    /**
     * Set client certificate
     * @param aClientCertNick Certificate Nick Name
     */
    public void setClientCertNick(String aClientCertNick) {
        clientCertNick = aClientCertNick;
        X509Certificate certs[];
        try {
            certs = cm.findCertsByNickname(clientCertNick);
            if (certs.length == 0) {
                System.out.println("unable to find cert nickname: " +
                        clientCertNick);
                System.exit(1);
            }
        } catch (TokenException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Return true if handshake is completed
     * else return false;
     * @return handshake status
     */
    public boolean isHandshakeCompleted() {
        return this.handshakeCompleted;
    }

    /**
     * Set handshakeCompleted flag to indicate
     * that the socket handshake is coplete.
     */
    public void setHandshakeCompleted() {
        this.handshakeCompleted = true;
    }

    /**
     * Clear handshakeCompleted flag to indicate
     * that the system is now ready for another
     * socket connection.
     */
    public void clearHandshakeCompleted() {
        this.handshakeCompleted = false;
    }

    /**
     * returns the total number SSLSockets created.
     */
    public int getSockTotal() {
        return sockID;
    }

    /**
     * ReadWrite thread class that takes a
     * SSLSocket as input and sleeps
     * for 1 sec between sending some test
     * data and receiving.
     */
    private class readWriteThread extends Thread {
        private SSLSocket clientSock = null;
        private String socketID   = null;
        private String ciphersuite;

        public readWriteThread(ThreadGroup tgOb,
                String tName, String cs, SSLSocket sock) {
            super(tgOb, tName);
            if (bVerbose) {
                System.out.println("New thread: " + this);
            }
            ciphersuite = cs;
            clientSock = sock;
            socketID = tName;
        }

        public void run() {

            try {
                String outputLine  = null;
                String inputLine   = null;
                InputStream  is    = clientSock.getInputStream();
                OutputStream os    = clientSock.getOutputStream();
                BufferedReader bir = new BufferedReader(
                        new InputStreamReader(is));
                PrintWriter out    = new PrintWriter(new BufferedWriter(
                        new OutputStreamWriter(os)));

                while (true) {
                    outputLine = ciphersuite + ":" + socketID + "\n";
                    if (bVerbose) {
                        System.out.println("Sending: " + outputLine);
                    }
                    out.print(outputLine);
                    out.flush();
                    inputLine = bir.readLine();
                    if (bVerbose) {
                        System.out.println("Received: " + inputLine +
                                " on Client-" + socketID);
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (java.net.SocketTimeoutException e) {
                e.printStackTrace();
            } catch (IOException e) {
                if ((e.getMessage().equalsIgnoreCase(
                        "SocketException cannot read on socket")) ||
                        (e.getMessage().equalsIgnoreCase(
                        "Socket has been closed, and cannot be reused.")) ) {
                    //System.out.println("SSLSocket "
                    //    + socketID + " has been closed.");
                } else e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    private void initJSS() {
        if (bJSS) {
            return; /* JSS already initialized */
        }
        try {
            cm  = CryptoManager.getInstance();

            if (cm.FIPSEnabled()) {
                System.out.println("The database is in FIPSMODE");
                bFipsMode = true;
            }
            tok = cm.getInternalKeyStorageToken();
            cb  = new FilePasswordCallback(fPasswordFile);
            tok.login(cb);
            bJSS = true;

        } catch (NotInitializedException ex) {
            ex.printStackTrace();
            System.exit(1);
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        } catch (TokenException ex) {
            ex.printStackTrace();
            System.exit(1);
        } catch (IncorrectPasswordException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    public boolean isServerAlive() {
        boolean isServerAlive = false;

        try {
            SSLSocket s = null;
            if (bVerbose) System.out.println("Confirming Server is alive ");

            //TLS_RSA_WITH_AES_128_CBC_SHA works in FIPS and Non FIPS mode.
            //and with JSS and JSSE SSL servers.
            setCipher(SSLSocket.TLS_RSA_WITH_AES_128_CBC_SHA);
            System.out.println("Testing Connection:" +
                    serverHost + ":" + port);
            for (int i = 0; i < 20; i++) {
                s = createSSLSocket();
                if (s != null) break;

                Thread.sleep(1000);
            }
            if (s != null) {
                s.close();
                isServerAlive = true;
            }

        } catch (InterruptedException ex) {
            ex.printStackTrace();
            System.exit(1);
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        return isServerAlive;
    }


    /**
     * sendServerShutdownMsg
     */
    public void sendServerShutdownMsg() {
        try {
            SSLSocket s = null;
            if (bVerbose) System.out.println("Sending shutdown message " +
                    "to server.");

            if (aWorkingCipher == 0) {
                System.out.println("no ciphersuite was able to connect to " +
                        "the server!");
                System.exit(1);
            }
            setCipher(aWorkingCipher);
            s = createSSLSocket();
            if (s == null) throw new IOException("Unable to connect to server");
            OutputStream os    = s.getOutputStream();
            PrintWriter out    = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(os)));
            out.println("shutdown");
            out.flush();
            out.close();
            s.close();
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }
    /**
     * closes the SSLSocket
     */
    public void closeAllSockets() {

        try {
            SSLSocket s;
            long start = System.currentTimeMillis();
            Iterator<SSLSocket> sIter = sockList.iterator();
            while (sIter.hasNext()) {
                s = sIter.next();
                s.close();
            }

            System.out.println("Waiting till all threads are dead");
            int i = 0;
            while (socketThreads.activeCount() > 0) {
                Thread.sleep(10);
                System.out.println("ActiveCount" + socketThreads.activeCount());
                //This loop should always exit but it has the potential
                //to hang the QA tests so...
                if (i == 100) { // 100 x 10
                    System.out.println("It is taking too long for the " +
                            "threads to die. Exiting the program");
                    System.out.println("Time taken: " +
                            (System.currentTimeMillis() - start) +
                            " Milliseconds");
                    /* while all the threads have not completed cleanup. */
                    /* the functional test of each configured cihpersuite */
                    /* successfully established a connection. */
                    /* Let remaining threads die when this application */
                    /* ends execution */
                    return;
                }
                i++;
            }
            System.out.println("All threads are dead. Time taken: " +
                    (System.currentTimeMillis() - start) + " Milliseconds.");
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);

        } catch (InterruptedException ex) {
            ex.printStackTrace();
            System.exit(1);

        }

    }

    /**
     * returns a connected SSLSocket or null if unable to connect.
     */
    private SSLSocket createSSLSocket() {
        SSLSocket sock = null;
        try {

            // connect to the server
            if ( bVerbose )
                System.out.println("client about to connect...");

            String hostAddr =
                    InetAddress.getByName(serverHost).getHostAddress();

            if ( bVerbose )
                System.out.println("the host " + serverHost +
                        " and the address " + hostAddr);

            if (TestCertCallBack) {
                if ( bVerbose )
                    System.out.println("calling approvalCallBack");
                sock = new SSLSocket(InetAddress.getByName(hostAddr),
                        port,
                        null,
                        0,
                        new TestCertificateApprovalCallback(),
                        null);
            } else {
                if ( bVerbose )
                    System.out.println("NOT calling approvalCallBack");
                sock = new SSLSocket(InetAddress.getByName(hostAddr),
                        port);
            }

            if (clientCertNick.equalsIgnoreCase("default")) {

                sock.setClientCertNickname("Client_RSA");
                sock.setClientCertNickname("Client_ECDSA");
                sock.setClientCertNickname("Client_DSS");
            } else {

                sock.setClientCertNickname(clientCertNick);
                if ( bVerbose ) {
                    System.out.println("Client specified cert by nickname");
                }

            }

            //Ensure the ciphersuite is disable, then enabled only it.
            if (sock.getCipherPreference(fCipher)) {
                System.out.println("Ciphersuite should have been disabled?");
                System.exit(1);

            } else {
                sock.setCipherPreference(fCipher, true);
            }

            sock.addHandshakeCompletedListener(
                    new HandshakeListener("client",this));

            sock.forceHandshake();
            sock.setSoTimeout(10*1000);
            sockList.add(sock);
            sockID++;
            aWorkingCipher = fCipher;
            if ( bVerbose ) {
                System.out.println("client connected");
            }

        } catch (SocketException ex) {
            if (bTestCiphers) {
                sock = null;
            } else {
                ex.printStackTrace();
                System.exit(1);
            }
        } catch (UnknownHostException ex) {
            ex.printStackTrace();
            System.exit(1);
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }

        return sock;

    }

    public void outputCipherResults() {
        String banner = new String
                ("\n-------------------------------------------------------\n");

        System.out.println(banner);
        System.out.println("JSS has " +
                org.mozilla.jss.ssl.SSLSocket.getImplementedCipherSuites().length +
                " ciphersuites and " +
                ciphersToTest.size() + " were configured and tested.");

        if (ciphersToTest.size() == h_ciphers.size()) {
            System.out.println("All " + ciphersToTest.size() +
                    " configured ciphersuites tested Successfully!\n");
        }

        if (!h_ciphers.isEmpty()) {
            if (!f_ciphers.isEmpty()) {
                System.out.println(banner);
                System.out.println(h_ciphers.size() +
                        " ciphersuites successfully connected to the "+
                        "server\n");
            }
            Iterator<String> iter = h_ciphers.iterator();
            while (iter.hasNext()) {
                System.out.println(iter.next());

            }
        }
        if (bFipsMode) {
            System.out.println("Note: ciphersuites that have the prefix " +
                    "\"SSL\" or \"SSL3\" were used in TLS mode.");
        }

        if (ciphersToTest.size()
        != (h_ciphers.size() + f_ciphers.size())) {
            System.out.println("ERROR: did not test all expected ciphersuites");
        }
        if (!f_ciphers.isEmpty()) {
            System.out.println(banner);
            System.out.println(f_ciphers.size() +
                    " ciphersuites that did not connect to the "+
                    "server\n\n");
            Iterator<String> iter = f_ciphers.iterator();
            while (iter.hasNext()) {
                System.out.println(iter.next());

            }
            System.out.println("we should have no failed ciphersuites!");
            System.exit(1);
        }

        System.out.println(banner);

    }
    /**
     * Initialize given number of SSLSocket client connection to the
     * SSLServer using the set parameters. Each Connection will have
     * a separate thread performing I/O to the Server.
     */
    public void createSSLConnections(int numToCreate) throws Exception {
        SSLSocket sock = null;
        initJSS();
        for (int i = 1; i <= numToCreate; i++) {
            sock =  createSSLSocket();
            if (sock != null) {
                String threadName = new String(sockID + "-" + i);
                readWriteThread rwThread = new readWriteThread(socketThreads,
                        threadName, ciphersuiteTested, sock);
                rwThread.start();
                if (i == 1) {
                    h_ciphers.add(ciphersuiteTested);
                }
            } else { /* ciphersuite failed */
                if (bTestCiphers) {
                    f_ciphers.add(ciphersuiteTested);
                }
                break;
            }
        }

        if ( bVerbose ) {
            System.out.println("Active thread count: " +
                    socketThreads.activeCount());
            System.out.println("Total threads created: " + getSockTotal());
        }
    }

    /**
     * SSL Handshake Listener implementation.
     */
    public class HandshakeListener
            implements SSLHandshakeCompletedListener {
        private String who;
        private JSS_SelfServClient boss;
        public HandshakeListener(String who, JSS_SelfServClient boss) {
            this.who = who;
            this.boss = boss;
        }
        public void handshakeCompleted(SSLHandshakeCompletedEvent event) {
            try {
                String mesg = who + " got a completed handshake ";
                SSLSecurityStatus status = event.getStatus();
                if( status.isSecurityOn() ) {
                    mesg += "(security is ON)";
                } else {
                    mesg += "(security is OFF)";
                }
                if ( bVerbose ) {
                    System.out.println(mesg);
                }
            } catch(Exception e) {
                e.printStackTrace();
                System.exit(1);
            }
            setHandshakeCompleted();
        }
    }

    /**
     * Set status return value to false.
     */
    public synchronized void setFailure() {
        success = false;
    }

    /**
     * Set status return value to success.
     */
    public synchronized boolean getSuccess() {
        return success;
    }

    /**
     * Main method. Used for unit testing.
     */
    public static void main(String[] args) {


        String  certnick   = "default";
        int  testCipher    = TEST_CIPHERS;
        String  testhost   = "localhost";
        int     testport   = 29754;
        int     numOfThreads = 10;
        String  certDbPath = null;
        String  passwdFile = null;
        boolean bVerbose = false;
        String server = "JSS";
        try {
            Thread.sleep(3*1000);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
        String  usage      = "\nUSAGE:\n" +
                "java org.mozilla.jss.tests.JSS_SelfServClient" +
                " [# sockets] [JSS cipher hex code \"0xC013\" value or -1] " +
                "\n\nOptional:\n" +
                "[certdb path] [password file] [server host] [server port]" +
                "[verbose] [server = JSS or JSSE] [ClientCert]";

        try {
            if (args.length <= 0 ||
                    args[0].toLowerCase().equals("-h")) {
                System.out.println(usage);
                System.exit(1);
            } else {
                numOfThreads = new Integer(args[0]).intValue();
                System.out.println("Number of Threads to create: "
                        + numOfThreads);
            }
            if (args.length >= 2) {
                if (args[1].startsWith("0x") || args[1].startsWith("0X")) {
                    testCipher = Integer.decode(args[1]).intValue();
                } else {
                    testCipher = new Integer(args[1]).intValue();
                }
            }
            if (args.length >= 3) {
                certDbPath = args[2];
            }
            if (args.length >= 4) {
                passwdFile = args[3];
            }
            if (args.length >= 5) {
                testhost   = args[4];
            }
            if (args.length >= 6) {
                testport   = new Integer(args[5]).intValue();
            }
            if ((args.length >= 7) && args[6].equalsIgnoreCase("verbose")
            == true) {
                System.out.println("verbose mode enabled.");
                bVerbose = true;
            }
            if (args.length >= 8) {

                server = args[7].toUpperCase();
            }
            if (args.length >=9) {
                certnick = args[8];
                System.out.println("certnickname: " + certnick);
            }


        } catch (Exception e) {
            System.out.println("Unknown exception : " + e.getMessage());
            System.exit(1);
        }

        System.out.println("Client connecting to server: " + testhost +
                ":" + testport);

        JSS_SelfServClient jssTest = new JSS_SelfServClient();
        try {
            if ( !testhost.equals("localhost") )
                jssTest.setHostName(testhost);

            if ( testport != 29754 )
                jssTest.setPort(testport);
            jssTest.setPasswordFile(passwdFile);
            jssTest.setCertDbPath(certDbPath);
            jssTest.setVerbose(bVerbose);
            jssTest.initJSS();

            if (!jssTest.testJSSCiphersMatchNSS()) {
                System.out.println("JSS needs to update the ciphersuites!");
                System.exit(1);
            }
            jssTest.setTestCertCallback(true);
            jssTest.configureDefaultSSLOptions();

            if ( certDbPath != null )
                jssTest.setCertDbPath(certDbPath);

            if ( passwdFile != null )
                jssTest.setPasswordFile(passwdFile);

            if (!jssTest.isServerAlive()) {
                System.out.println("Server " + testhost + ":" +
                        testport + " is not Alive.\nIf this test was ran by " +
                        "all.pl look at the server invocation for failures " +
                        "and check network issues.");
                System.exit(1);
            }

            if (testCipher != TEST_CIPHERS) {
                jssTest.setClientCertNick(certnick);
                jssTest.setTestCiphers(false);
                jssTest.setCipher(testCipher);
                jssTest.createSSLConnections(numOfThreads);
            } else {
                jssTest.configureCipherSuites(server);
                jssTest.testCiphersuites(numOfThreads);
            }
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            ex.printStackTrace();
            System.exit(1);
        }

        if (jssTest.getSockTotal() == 0 ) {
            System.out.println("No SSLSockets created check your " +
                    "configuration.");
            System.exit(1);
        }

        // choose how to exit the program
        System.out.println(jssTest.getSockTotal() + " SSLSockets created.");
        System.out.println("Each created SSLSocket is reading/writing to" +
                " the SSLServer.");

        if (jssTest.getTestCiphers()) {
            try {
                //Sleep for 30 seconds
                Thread.sleep(30*1000);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
                System.exit(1);
            }
            jssTest.closeAllSockets();
            jssTest.sendServerShutdownMsg();
            jssTest.outputCipherResults();
            System.exit(0);
        }

        System.out.println("You can choose to exit the program enter:" +
                "\n\t\'A\' to abort with out closing the sockets." +
                "\n\t\'C\' to close all client sockets (server will not quit)" +
                "\n\tor any other letter to close all sockets and tell the" +
                "server to quit.");

        try {
            BufferedReader stdin = new BufferedReader(new
                    InputStreamReader(System.in));
            String p = stdin.readLine();
            if (p.equalsIgnoreCase("a")) {
                System.out.println("Aborting with out closing SSLSockets.");
            } else {
                jssTest.closeAllSockets();
                if (!p.equalsIgnoreCase("c")) {
                    //shutdown the server
                    jssTest.sendServerShutdownMsg();
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }

        System.exit(0);
    }
}
