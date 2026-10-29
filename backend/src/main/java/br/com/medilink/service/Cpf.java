package br.com.medilink.service;

/** Normalização e validação dos dígitos verificadores do CPF. */
public final class Cpf {

	private Cpf() {
	}

	/** Mantém só os dígitos ("123.456.789-09" -> "12345678909"). */
	public static String normalizar(String cpf) {
		return cpf == null ? "" : cpf.replaceAll("\\D", "");
	}

	public static boolean valido(String cpf) {
		String d = normalizar(cpf);
		if (d.length() != 11 || d.chars().distinct().count() == 1) {
			return false;
		}
		return digito(d, 9) == d.charAt(9) - '0' && digito(d, 10) == d.charAt(10) - '0';
	}

	private static int digito(String d, int tamanho) {
		int soma = 0;
		for (int i = 0; i < tamanho; i++) {
			soma += (d.charAt(i) - '0') * (tamanho + 1 - i);
		}
		int resto = (soma * 10) % 11;
		return resto == 10 ? 0 : resto;
	}
}
