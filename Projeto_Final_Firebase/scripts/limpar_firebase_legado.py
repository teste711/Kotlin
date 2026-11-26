#!/usr/bin/env python3
"""
Script de limpeza de dados legados do Firebase (Firestore + Realtime Database)
para deixar o banco no esquema final do Projeto C.A.S.A. (ESP32-S3 v2.10.0).

Uso:
  pip install firebase-admin
  python scripts/limpar_firebase_legado.py \
      --cred caminho/para/serviceAccountKey.json \
      --rtdb-url https://SEU-PROJETO-default-rtdb.firebaseio.com

Opções:
  --full-reset   Além das coleções legadas, limpa também dispositivos, arquivos_midia,
                 usuarios/*/categorias, usuarios/*/midias, usuarios/*/chaves_dispositivos
                 e todo o nó /dispositivo/controle no RTDB (ideal para ambiente de testes).
  --dry-run      Apenas mostra o que seria removido, sem alterar nada.
"""

import argparse
import sys
import firebase_admin
from firebase_admin import credentials, firestore, db


COLECOES_LEGADAS_RAIZ = [
    "categoria",
    "categoria_botao",
    "pareamentos",
    "arquivos_esp",
]

COLECOES_RESET_TOTAL = [
    "arquivos_midia",
    "dispositivos",
]


def apagar_colecao_recursivo(client, col_ref, dry_run: bool, batch_size: int = 200) -> int:
    total = 0
    while True:
        docs = list(col_ref.limit(batch_size).stream())
        if not docs:
            break
        for doc in docs:
            # Apaga subcoleções primeiro (ex: arquivos_midia/{arquivo}/blocos)
            for subcol in doc.reference.collections():
                total += apagar_colecao_recursivo(client, subcol, dry_run, batch_size)
            if dry_run:
                print(f"[DRY-RUN] Deletaria {doc.reference.path}")
            else:
                doc.reference.delete()
            total += 1
        if dry_run:
            break
    return total


def main():
    parser = argparse.ArgumentParser(description="Limpa dados legados do Firebase (Projeto C.A.S.A. v2.10.0)")
    parser.add_argument("--cred", required=True, help="Caminho para o JSON da Service Account do Firebase")
    parser.add_argument("--rtdb-url", required=True, help="URL do Firebase Realtime Database")
    parser.add_argument("--full-reset", action="store_true", help="Apaga também dados de teste das coleções canônicas")
    parser.add_argument("--dry-run", action="store_true", help="Simula sem apagar nada")
    args = parser.parse_args()

    cred = credentials.Certificate(args.cred)
    firebase_admin.initialize_app(cred, {"databaseURL": args.rtdb_url})
    fs = firestore.client()

    print("=== 1. Removendo coleções legadas na raiz do Firestore ===")
    for nome_col in COLECOES_LEGADAS_RAIZ:
        qtd = apagar_colecao_recursivo(fs, fs.collection(nome_col), args.dry_run)
        print(f"  - /{nome_col}: {qtd} documento(s) {'encontrado(s)' if args.dry_run else 'removido(s)'}")

    if args.full_reset:
        print("\n=== 2. Reset total das coleções de teste (/arquivos_midia, /dispositivos) ===")
        for nome_col in COLECOES_RESET_TOTAL:
            qtd = apagar_colecao_recursivo(fs, fs.collection(nome_col), args.dry_run)
            print(f"  - /{nome_col}: {qtd} documento(s) {'encontrado(s)' if args.dry_run else 'removido(s)'}")

    print("\n=== 3. Padronizando documentos em /usuarios ===")
    usuarios_docs = list(fs.collection("usuarios").stream())
    for udoc in usuarios_docs:
        data = udoc.to_dict() or {}
        updates = {}

        if "dispositivosVinculados" in data:
            updates["dispositivosVinculados"] = firestore.DELETE_FIELD
            if not args.full_reset:
                existentes = list(set((data.get("dispositivos") or []) + (data.get("dispositivosVinculados") or [])))
                updates["dispositivos"] = existentes

        if args.full_reset:
            updates["dispositivos"] = []
            for sub in ("categorias", "midias", "chaves_dispositivos"):
                qtd = apagar_colecao_recursivo(fs, udoc.reference.collection(sub), args.dry_run)
                if qtd:
                    print(f"  - usuarios/{udoc.id}/{sub}: {qtd} doc(s) {'encontrado(s)' if args.dry_run else 'removido(s)'}")

        if updates:
            if args.dry_run:
                print(f"[DRY-RUN] Atualizaria usuarios/{udoc.id}: {list(updates.keys())}")
            else:
                udoc.reference.update(updates)
                print(f"  - usuarios/{udoc.id} padronizado.")

    print("\n=== 4. Limpando Realtime Database (/dispositivo/controle) ===")
    ref_controle = db.reference("dispositivo/controle")
    if args.full_reset:
        if args.dry_run:
            print("[DRY-RUN] Removeria todo o nó /dispositivo/controle")
        else:
            ref_controle.delete()
            print("  - /dispositivo/controle removido por completo.")
    else:
        for chave_legada in ("atualizar", "timestamp"):
            if args.dry_run:
                print(f"[DRY-RUN] Removeria /dispositivo/controle/{chave_legada}")
            else:
                ref_controle.child(chave_legada).delete()
        print("  - Chaves globais legadas (atualizar/timestamp) removidas de /dispositivo/controle.")

    print("\nConcluído!")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Erro: {exc}", file=sys.stderr)
        sys.exit(1)
