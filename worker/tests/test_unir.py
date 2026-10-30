from medilink_worker.unir import unir


def test_alinha_as_fontes_pelo_instante_de_abertura_e_ordena_as_falas():
    medico = ("MEDICO", 10_000, [(0.0, 2.0, "Bom dia, como o senhor está?"), (6.0, 7.5, "Entendi.")])
    paciente = ("PACIENTE", 12_000, [(0.5, 3.0, "Bom dia, doutora.")])  # o arquivo do paciente abriu 2 s depois

    falas, duracao = unir([paciente, medico])

    assert [(f.papel, f.inicio_ms, f.fim_ms) for f in falas] == [
        ("MEDICO", 0, 2000), ("PACIENTE", 2500, 5000), ("MEDICO", 6000, 7500)]
    assert [f.texto for f in falas][1] == "Bom dia, doutora."
    assert duracao == 7500


def test_falas_sobrepostas_das_duas_pessoas_sao_mantidas():
    falas, _ = unir([("MEDICO", 0, [(1.0, 4.0, "Então o senhor sente")]), ("PACIENTE", 0, [(2.0, 3.0, "Sim")])])

    assert [f.papel for f in falas] == ["MEDICO", "PACIENTE"]
    assert falas[0].fim_ms > falas[1].inicio_ms


def test_texto_vazio_e_descartado_e_espacos_sao_aparados():
    falas, duracao = unir([("MEDICO", 0, [(0, 1, "   "), (1, 2, "  oi  ")])])

    assert [f.texto for f in falas] == ["oi"]
    assert duracao == 2000


def test_sem_fontes_ou_sem_falas():
    assert unir([]) == ([], 0)
    assert unir([("MEDICO", 5, [])]) == ([], 0)
