package br.com.medilink.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Só endereços de internet pública são destino de webhook (defesa contra SSRF): nada de loopback, redes privadas,
 * link-local (inclui os metadados de nuvem, 169.254.169.254), CGNAT, multicast, documentação/teste e reservados.
 * IPv6 mapeado em IPv4 é avaliado como o IPv4 que carrega.
 */
public final class EnderecoPublico {

	private EnderecoPublico() {
	}

	public static boolean permitido(InetAddress a) {
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
			return false;
		}
		byte[] b = a.getAddress();
		if (a instanceof Inet4Address) {
			return ipv4Publico(b);
		}
		if (a instanceof Inet6Address) {
			boolean mapeado = true;
			for (int i = 0; i < 10; i++) {
				mapeado &= b[i] == 0;
			}
			mapeado &= (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
			if (mapeado) {
				return ipv4Publico(new byte[] {b[12], b[13], b[14], b[15]});
			}
			int p = b[0] & 0xff;
			if ((p & 0xfe) == 0xfc) { // fc00::/7 (ULA)
				return false;
			}
			if (p == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) { // 2001:db8::/32 (documentação)
				return false;
			}
			return p != 0x00; // ::/8 (inclui :: e ::1) reservado
		}
		return false;
	}

	private static boolean ipv4Publico(byte[] b) {
		int a = b[0] & 0xff, c = b[1] & 0xff, d = b[2] & 0xff;
		if (a == 0 || a == 10 || a == 127 || a >= 224) { // 0/8, 10/8, 127/8, multicast e reservado (224+, 240/4)
			return false;
		}
		if (a == 100 && c >= 64 && c <= 127) { // CGNAT 100.64/10
			return false;
		}
		if (a == 169 && c == 254) { // link-local
			return false;
		}
		if (a == 172 && c >= 16 && c <= 31) {
			return false;
		}
		if (a == 192 && c == 168) {
			return false;
		}
		if (a == 192 && c == 0 && (d == 0 || d == 2)) { // 192.0.0/24 e 192.0.2/24 (TEST-NET-1)
			return false;
		}
		if (a == 198 && (c == 18 || c == 19)) { // benchmark 198.18/15
			return false;
		}
		if (a == 198 && c == 51 && d == 100) { // TEST-NET-2
			return false;
		}
		return !(a == 203 && c == 0 && d == 113); // TEST-NET-3
	}
}
