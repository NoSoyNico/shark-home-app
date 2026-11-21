package mx.nico.sharkhome;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;

/**
 * Cliente ADB mínimo (protocolo público) para conectarse al adbd LOCAL de la troca (127.0.0.1:5555),
 * disparar el diálogo "Permitir depuración" la primera vez, y correr comandos como usuario shell.
 * Igual que hace Electro: así la app puede auto-concederse READ_LOGS y el listener de música SIN una compu.
 * NO copia código de Electro; implementa el protocolo ADB documentado. Solo lectura/otorgar permisos propios.
 */
public class AdbClient {
    static final String TAG = "SharkAdb";

    // Comandos del protocolo (little-endian)
    static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    static final int AUTH_TOKEN = 1, AUTH_SIG = 2, AUTH_RSAPUB = 3;
    static final int VERSION = 0x01000000, MAXDATA = 256 * 1024;
    // DigestInfo ASN.1 de SHA1 (adb firma el token de 20 bytes con PKCS#1 v1.5 + este prefijo)
    static final byte[] SHA1_ASN1 = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    public interface Progress { void line(String s); }

    final Context ctx;
    Socket sock;
    InputStream in;
    OutputStream out;
    RSAPrivateKey priv;
    RSAPublicKey pub;
    int localId = 1;

    public AdbClient(Context c) { ctx = c.getApplicationContext(); }

    /** Flujo completo: autenticar y otorgar los permisos. Devuelve un texto con el resultado. */
    public String grantAll(Progress p) {
        Log.i(TAG, "grantAll: inicio");
        String res = runShell(new String[] {
                "pm grant mx.nico.sharkhome android.permission.READ_LOGS 2>&1; echo RL=$?\n",
                "pm grant mx.nico.sharkhome android.permission.READ_EXTERNAL_STORAGE 2>&1; echo RS=$?\n",
                "cmd notification allow_listener mx.nico.sharkhome/mx.nico.sharkhome.MediaListener 2>&1; echo NL=$?\n"
        }, p);
        Log.i(TAG, "grant result: " + res);
        return res;
    }

    /**
     * Autentica con el adbd local (diálogo "Permitir" la 1a vez) y corre uno o más comandos como shell.
     * Reutilizable: lo usan tanto grantAll como el interruptor del asistente de voz. Devuelve la salida.
     */
    public String runShell(String[] cmds, Progress p) {
        try {
            loadOrCreateKey();
            p.line("Conectando al ADB local…");
            if (!connectAndAuth("127.0.0.1", 5555, p)) return "No se pudo autenticar con el ADB";
            p.line("Autorizado.");
            StringBuilder r = new StringBuilder();
            for (String c : cmds) r.append(shell(c));
            return r.toString();
        } catch (Throwable t) {
            Log.w(TAG, "runShell: " + t);
            return "Error: " + t;
        } finally {
            try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        }
    }

    // ---------- llave RSA (se guarda y se reutiliza, como el adbkey de una compu) ----------
    // IMPORTANTE: la llave DEBE ser estable entre arranques. Si se regenera cada vez, adbd pide
    // "Permitir" una y otra vez y nunca queda autorizada. Por eso guardamos privada (PKCS8) Y
    // pública (X509) en archivos y las recargamos ambas, sin depender de parámetros CRT.
    void loadOrCreateKey() throws Exception {
        File fPriv = new File(ctx.getFilesDir(), "adbkey_priv.der");
        File fPub = new File(ctx.getFilesDir(), "adbkey_pub.der");
        if (fPriv.exists() && fPub.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                priv = (RSAPrivateKey) kf.generatePrivate(
                        new PKCS8EncodedKeySpec(readAll(new FileInputStream(fPriv))));
                pub = (RSAPublicKey) kf.generatePublic(
                        new java.security.spec.X509EncodedKeySpec(readAll(new FileInputStream(fPub))));
            } catch (Throwable t) {
                Log.w(TAG, "no se pudo recargar la llave, regenero: " + t);
                priv = null; pub = null;
            }
        }
        if (priv == null || pub == null) {
            Log.i(TAG, "generando llave ADB nueva (se guarda y reutiliza)");
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            priv = (RSAPrivateKey) kp.getPrivate();
            pub = (RSAPublicKey) kp.getPublic();
            FileOutputStream a = new FileOutputStream(fPriv); a.write(priv.getEncoded()); a.close(); // PKCS8
            FileOutputStream b = new FileOutputStream(fPub);  b.write(pub.getEncoded());  b.close(); // X509
        }
    }

    byte[] signToken(byte[] token) throws Exception {
        // adbd verifica con RSA_verify(NID_sha1, token, sig): espera PKCS#1 v1.5 tipo-1 sobre
        // DigestInfo = prefijo ASN.1 de SHA1 + token (20 bytes, tratados como si fueran el hash).
        byte[] data = new byte[SHA1_ASN1.length + token.length];
        System.arraycopy(SHA1_ASN1, 0, data, 0, SHA1_ASN1.length);
        System.arraycopy(token, 0, data, SHA1_ASN1.length, token.length);
        // NONEwithRSA aplica el relleno PKCS#1 tipo-1 y firma los bytes crudos (sin añadir otro hash),
        // que es justo lo que espera adbd. Es más fiable que Cipher privado (que en Android puede usar tipo-2).
        java.security.Signature s = java.security.Signature.getInstance("NONEwithRSA");
        s.initSign(priv);
        s.update(data);
        return s.sign();
    }

    /** Clave pública en el formato "android_pubkey" (524 bytes) + base64 + nombre, como lo espera adbd. */
    byte[] pubkeyPayload() throws Exception {
        final int WORDS = 64; // 2048/32
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32)); // -1/n0 mod 2^32
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);       // (2^2048)^2 mod n
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + WORDS * 4 + WORDS * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(WORDS);
        bb.putInt((int) n0inv.longValue());
        putWordsLE(bb, n, WORDS);
        putWordsLE(bb, rr, WORDS);
        bb.putInt(e.intValue());
        String b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        byte[] name = (" shark@byd\u0000").getBytes("UTF-8");
        byte[] k = b64.getBytes("UTF-8");
        byte[] payload = new byte[k.length + name.length];
        System.arraycopy(k, 0, payload, 0, k.length);
        System.arraycopy(name, 0, payload, k.length, name.length);
        return payload;
    }

    static void putWordsLE(ByteBuffer bb, BigInteger v, int words) {
        BigInteger mask = BigInteger.valueOf(0xffffffffL);
        for (int i = 0; i < words; i++) {
            long w = v.shiftRight(32 * i).and(mask).longValue();
            bb.putInt((int) w);
        }
    }

    // ---------- protocolo ----------
    boolean connectAndAuth(String host, int port, Progress p) throws Exception {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 4000);
        sock.setTcpNoDelay(true);
        sock.setSoTimeout(90000); // hasta 90 s para que el usuario toque "Permitir"
        in = new BufferedInputStream(sock.getInputStream());
        out = new BufferedOutputStream(sock.getOutputStream());
        send(A_CNXN, VERSION, MAXDATA, "host::features=shell_v2,cmd\u0000".getBytes("UTF-8"));
        boolean sentPub = false;   // la llave pública se manda UNA sola vez (dispara el diálogo)
        boolean signedOnce = false;
        while (true) {
            Msg m = read();
            Log.i(TAG, "rx cmd=" + Integer.toHexString(m.cmd) + " arg0=" + m.arg0 + " len=" + m.len);
            if (m.cmd == A_CNXN) { Log.i(TAG, "CNXN: autenticado"); return true; } // conectado
            if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                // Firmar SIEMPRE que se pueda: tras aceptar el diálogo, adbd manda un token NUEVO
                // y espera la FIRMA con la llave ya autorizada (no la pública otra vez).
                if (!signedOnce) {
                    signedOnce = true;
                    Log.i(TAG, "AUTH_TOKEN -> firmo");
                    send(A_AUTH, AUTH_SIG, 0, signToken(m.data));
                } else if (!sentPub) {
                    sentPub = true;
                    Log.i(TAG, "AUTH_TOKEN -> mando llave pública (diálogo)");
                    p.line("Toca “Permitir” en la pantalla de la troca…");
                    send(A_AUTH, AUTH_RSAPUB, 0, pubkeyPayload()); // 1a vez: dispara el diálogo
                } else {
                    // Ya se mandó la pública y el usuario aceptó: ahora firmar el nuevo reto.
                    Log.i(TAG, "AUTH_TOKEN (post-permiso) -> firmo de nuevo");
                    send(A_AUTH, AUTH_SIG, 0, signToken(m.data));
                }
            }
            // otros mensajes: ignorar y seguir leyendo
        }
    }

    String shell(String cmd) throws IOException {
        int lid = localId++;
        send(A_OPEN, lid, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY) {
                // stream aceptado (arg0 = id remoto)
            } else if (m.cmd == A_WRTE) {
                sb.append(new String(m.data, "UTF-8"));
                send(A_OKAY, lid, m.arg0, null); // ack
            } else if (m.cmd == A_CLSE) {
                send(A_CLSE, lid, m.arg0, null);
                break;
            }
        }
        return sb.toString();
    }

    void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd);
        h.putInt(arg0);
        h.putInt(arg1);
        h.putInt(data == null ? 0 : data.length);
        h.putInt(data == null ? 0 : checksum(data));
        h.putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        if (data != null && data.length > 0) out.write(data);
        out.flush();
    }

    static int checksum(byte[] d) {
        int s = 0;
        for (byte b : d) s += (b & 0xff);
        return s;
    }

    static class Msg { int cmd, arg0, arg1, len; byte[] data; }

    Msg read() throws IOException {
        byte[] hb = readFully(24);
        ByteBuffer b = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        m.len = b.getInt();
        b.getInt(); // crc
        b.getInt(); // magic
        m.data = m.len > 0 ? readFully(m.len) : new byte[0];
        return m;
    }

    byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException("adb cerró la conexión");
            off += r;
        }
        return b;
    }

    static byte[] readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
        is.close();
        return bos.toByteArray();
    }
}
