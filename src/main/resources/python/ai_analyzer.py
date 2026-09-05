#!/usr/bin/env python3
"""
Advanced analyzer pipeline with optional pgvector and OCR support:
- text cleaning
- sentence segmentation
- fingerprint (shingle) creation
- similarity calculations (rapidfuzz)
- paraphrase detection using sentence-transformers embeddings + semantic similarity
- optional: store/query embeddings in PostgreSQL using pgvector
- optional: OCR from images using Tesseract (pytesseract) or PaddleOCR

Usage examples:
  - Analyze raw text (stdin or --text)
  - --image /path/to/img.jpg  -> perform OCR then analyze
  - --pg-uri postgresql://... --pg-table docs --store-doc DOCID -> store doc-level embedding
  - --pg-uri ... --pg-table docs --query-k 5 --text "some query" -> return nearest docs

Requires (optional):
  - sentence-transformers (for embeddings)
  - numpy
  - rapidfuzz
  - psycopg2 (or psycopg) for PostgreSQL pgvector integration
  - pillow + pytesseract or paddlepaddle + paddleocr for OCR

Script is robust: falls back to lightweight heuristics if heavy libs are missing.
"""

import sys
import json
import re
import math
import hashlib
import os
import warnings
import contextlib
from functools import lru_cache

for stream in (sys.stdin, sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

MAX_SENTENCES = 500
DEFAULT_CHUNK_MAX_CHARS = 1200
DEFAULT_CHUNK_OVERLAP_SENTENCES = 1
DEFAULT_OCR_MAX_PAGES = int(os.getenv("ANALYSIS_OCR_MAX_PAGES", "50"))
DEFAULT_OCR_WORKERS = int(os.getenv("ANALYSIS_OCR_WORKERS", "2"))
DEFAULT_OCR_LANGUAGES = os.getenv("ANALYSIS_OCR_LANGUAGES", "fra+eng")
DEFAULT_OCR_MIN_SUCCESS_RATE = float(os.getenv("ANALYSIS_OCR_MIN_SUCCESS_RATE", "0.60"))
DEFAULT_MIN_TEXT_LENGTH = int(os.getenv("ANALYSIS_MIN_TEXT_LENGTH", "80"))
DEFAULT_SEMANTIC_MATCH_THRESHOLD = float(os.getenv("ANALYSIS_SEMANTIC_MATCH_THRESHOLD", "0.78"))
USE_PADDLEOCR = os.getenv("ANALYSIS_USE_PADDLEOCR", "false").lower() in ("1", "true", "yes")

warnings.filterwarnings("ignore")
os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")

# optional heavy libraries
HAS_NUMPY = False
HAS_RAPIDFUZZ = False
HAS_SBERT = False
HAS_SPACY = False
HAS_TESSERACT = False
HAS_PADDLEOCR = False
HAS_PYMUPDF = False
HAS_PSYCOPG = False

try:
    import numpy as np
    HAS_NUMPY = True
except Exception:
    np = None

try:
    from rapidfuzz import fuzz
    HAS_RAPIDFUZZ = True
except Exception:
    fuzz = None

try:
    from sentence_transformers import SentenceTransformer
    HAS_SBERT = True
except Exception:
    SentenceTransformer = None

try:
    import spacy
    HAS_SPACY = True
    try:
        nlp = spacy.load("en_core_web_sm")
    except Exception:
        nlp = None
except Exception:
    spacy = None
    nlp = None

# OCR libraries
try:
    from PIL import Image
    import pytesseract
    HAS_TESSERACT = True
except Exception:
    pytesseract = None
    Image = None

if USE_PADDLEOCR:
    try:
        from paddleocr import PaddleOCR
        HAS_PADDLEOCR = True
    except Exception:
        PaddleOCR = None
else:
    PaddleOCR = None

try:
    with contextlib.redirect_stdout(sys.stderr):
        try:
            import pymupdf as fitz
        except Exception:
            import fitz
    HAS_PYMUPDF = True
except Exception:
    fitz = None

# Postgres client
try:
    import psycopg2
    from psycopg2.extras import execute_values
    HAS_PSYCOPG = True
except Exception:
    psycopg2 = None
    execute_values = None


def clean_text(text: str) -> str:
    text = re.sub(r"[\ud800-\udfff]", "", str(text))
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"[\x00-\x1f\x7f]+", "", text)
    return text.strip()


def parse_ocr_languages(languages: str):
    values = []
    for candidate in str(languages or '').split(','):
        candidate = candidate.strip()
        if candidate and candidate not in values:
            values.append(candidate)
    for fallback in ('fra+eng', 'fra', 'eng'):
        if fallback not in values:
            values.append(fallback)
    return values


def paddle_language_from_tesseract(languages: str):
    first = parse_ocr_languages(languages)[0].split('+')[0]
    if first == 'fra':
        return 'fr'
    if first in ('eng', 'en'):
        return 'en'
    return 'fr'


def text_quality_score(text: str):
    cleaned = clean_text(text)
    if not cleaned:
        return 0.0
    alpha_num = sum(1 for char in cleaned if char.isalnum())
    words = re.findall(r'\w+', cleaned, flags=re.UNICODE)
    return min(1.0, (alpha_num / max(1, len(cleaned))) * 0.55 + min(1.0, len(words) / 120.0) * 0.45)


def split_sentences(text: str):
    if HAS_SPACY and nlp is not None:
        doc = nlp(text)
        return [sent.text.strip() for sent in doc.sents if sent.text.strip()]
    cand = re.split(r'(?<=[.!?;])\s+', text)
    return [s.strip() for s in cand if s.strip()]


def semantic_chunks(text: str, max_chars=DEFAULT_CHUNK_MAX_CHARS, overlap_sentences=DEFAULT_CHUNK_OVERLAP_SENTENCES):
    """
    Split text into semantic chunks using sentence boundaries.

    A chunk is small enough for embedding models but still keeps complete
    sentences. A small sentence overlap preserves context between chunks.
    """
    sentences = split_sentences(clean_text(text))
    if not sentences:
        return []

    chunks = []
    current = []
    current_len = 0

    for sentence in sentences:
        sentence_len = len(sentence)
        if current and current_len + 1 + sentence_len > max_chars:
            chunks.append(' '.join(current).strip())
            overlap = current[-overlap_sentences:] if overlap_sentences > 0 else []
            current = list(overlap)
            current_len = sum(len(s) for s in current) + max(0, len(current) - 1)

        current.append(sentence)
        current_len += sentence_len + (1 if current_len else 0)

    if current:
        chunks.append(' '.join(current).strip())

    return [chunk for chunk in chunks if chunk]


def shingles(sentence: str, k=5):
    s = re.sub(r"\s+", " ", sentence)
    s = s.lower()
    return {s[i:i+k] for i in range(max(0, len(s)-k+1))}


def jaccard(a:set, b:set):
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    inter = len(a & b)
    uni = len(a | b)
    return inter / uni if uni>0 else 0.0


def compute_fingerprint_similarity(sentences):
    shingle_sets = [shingles(s, k=5) for s in sentences]
    n = len(sentences)
    pairs = []
    for i in range(n):
        for j in range(i+1, n):
            score = jaccard(shingle_sets[i], shingle_sets[j])
            if score > 0.2:
                pairs.append({'i': i, 'j': j, 'score': score, 'si': sentences[i], 'sj': sentences[j]})
    pairs.sort(key=lambda x: x['score'], reverse=True)
    return pairs


def compute_lexical_similarity(sentences):
    n = len(sentences)
    pairs = []
    if not HAS_RAPIDFUZZ:
        return pairs
    for i in range(n):
        for j in range(i+1, n):
            score = fuzz.token_set_ratio(sentences[i], sentences[j]) / 100.0
            if score > 0.6:
                pairs.append({'i': i, 'j': j, 'score': score, 'si': sentences[i], 'sj': sentences[j]})
    pairs.sort(key=lambda x: x['score'], reverse=True)
    return pairs


@lru_cache(maxsize=2)
def load_sentence_model(model_name):
    return SentenceTransformer(model_name)


def compute_semantic_similarity(sentences, model_name='all-MiniLM-L6-v2', threshold=0.75):
    if not HAS_SBERT:
        return {'pairs': [], 'avg': None}
    try:
        model = load_sentence_model(model_name)
        embeddings = model.encode(sentences, convert_to_numpy=HAS_NUMPY)
        if HAS_NUMPY:
            norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
            norms[norms==0] = 1.0
            embn = embeddings / norms
            sim_matrix = np.dot(embn, embn.T)
            n = len(sentences)
            pairs = []
            for i in range(n):
                for j in range(i+1, n):
                    sim = float(sim_matrix[i, j])
                    if sim >= threshold:
                        pairs.append({'i': i, 'j': j, 'score': sim, 'si': sentences[i], 'sj': sentences[j]})
            avg = float(sim_matrix[np.triu_indices(n, k=1)].mean()) if n>1 else None
            pairs.sort(key=lambda x: x['score'], reverse=True)
            return {'pairs': pairs, 'avg': avg, 'embeddings': embeddings}
        else:
            # embeddings as lists
            embs = [list(e) for e in embeddings]
            # compute cosine manually (cheap for small n)
            n = len(embs)
            pairs = []
            sims = []
            for i in range(n):
                for j in range(i+1, n):
                    a = embs[i]
                    b = embs[j]
                    num = sum(x*y for x,y in zip(a,b))
                    da = math.sqrt(sum(x*x for x in a))
                    db = math.sqrt(sum(y*y for y in b))
                    denom = da*db if da*db>0 else 1.0
                    sim = num/denom
                    sims.append(sim)
                    if sim >= threshold:
                        pairs.append({'i': i, 'j': j, 'score': sim, 'si': sentences[i], 'sj': sentences[j]})
            avg = sum(sims)/len(sims) if sims else None
            pairs.sort(key=lambda x: x['score'], reverse=True)
            return {'pairs': pairs, 'avg': avg, 'embeddings': embeddings}
    except Exception as e:
        return {'pairs': [], 'avg': None, 'error': str(e)}


def detect_ai_tokens(text: str):
    """Return explicit AI-related mentions, not an AI-authorship prediction."""
    patterns = (r'\bgpt\b', r'\bchatgpt\b', r'\bopenai\b', r'\bllm\b',
                r'\bai\b', r'\bauto-generated\b')
    lowered = text.lower()
    return sum(len(re.findall(pattern, lowered)) for pattern in patterns)


def score_from_findings(num_sentences:int, fingerprint_pairs, lexical_pairs, semantic_info, ai_token_count:int):
    fp_score = min(100, len(fingerprint_pairs) * 15)
    lex_score = min(100, len(lexical_pairs) * 10)
    sem_score = 0
    if semantic_info.get('avg') is not None:
        sem_score = int(semantic_info['avg'] * 100)
    ai_score = min(100, ai_token_count * 25)
    overall = int(min(100, fp_score * 0.45 + lex_score * 0.25 + sem_score * 0.3))
    return overall, ai_score


# === OCR helpers ===

def ocr_image(path_or_bytes, languages=DEFAULT_OCR_LANGUAGES):
    """Return extracted text or empty string."""
    # try pytesseract
    if HAS_TESSERACT and Image is not None:
        try:
            if isinstance(path_or_bytes, (bytes, bytearray)):
                from io import BytesIO
                img = Image.open(BytesIO(path_or_bytes))
            else:
                img = Image.open(path_or_bytes)
        except Exception:
            img = None
        if img is not None:
            for language in parse_ocr_languages(languages):
                try:
                    text = pytesseract.image_to_string(img, lang=language)
                    if text and clean_text(text):
                        return text
                except Exception:
                    continue
    # try PaddleOCR
    if USE_PADDLEOCR and HAS_PADDLEOCR:
        try:
            with contextlib.redirect_stdout(sys.stderr):
                ocr = PaddleOCR(use_textline_orientation=True, lang=paddle_language_from_tesseract(languages))
            if isinstance(path_or_bytes, (bytes, bytearray)):
                from io import BytesIO
                img = Image.open(BytesIO(path_or_bytes))
                import tempfile
                fd, tmp_path = tempfile.mkstemp(suffix='.png')
                os.close(fd)
                img.save(tmp_path)
                try:
                    with contextlib.redirect_stdout(sys.stderr):
                        res = ocr.ocr(tmp_path)
                finally:
                    try:
                        os.remove(tmp_path)
                    except Exception:
                        pass
            else:
                with contextlib.redirect_stdout(sys.stderr):
                    res = ocr.ocr(path_or_bytes)
            lines = []
            for r in res:
                for line in r:
                    lines.append(line[1][0])
            return '\n'.join(lines)
        except Exception:
            pass
    return ''


def ocr_image_with_metadata(path_or_bytes, languages=DEFAULT_OCR_LANGUAGES):
    metadata = {
        'engine': None,
        'engine_version': None,
        'languages_requested': parse_ocr_languages(languages),
        'language_used': None,
        'success': False,
        'error': None
    }

    if HAS_TESSERACT and Image is not None:
        try:
            metadata['engine'] = 'tesseract'
            try:
                metadata['engine_version'] = str(pytesseract.get_tesseract_version())
            except Exception:
                metadata['engine_version'] = None

            if isinstance(path_or_bytes, (bytes, bytearray)):
                from io import BytesIO
                img = Image.open(BytesIO(path_or_bytes))
            else:
                img = Image.open(path_or_bytes)

            try:
                for language in parse_ocr_languages(languages):
                    try:
                        text = pytesseract.image_to_string(img, lang=language)
                        if text and clean_text(text):
                            metadata['language_used'] = language
                            metadata['success'] = True
                            return text, metadata
                    except Exception as exception:
                        metadata['error'] = str(exception)
                return '', metadata
            finally:
                img.close()
        except Exception as exception:
            metadata['error'] = str(exception)

    if USE_PADDLEOCR and HAS_PADDLEOCR:
        try:
            metadata['engine'] = 'paddleocr'
            metadata['language_used'] = paddle_language_from_tesseract(languages)
            text = ocr_image(path_or_bytes, languages=languages)
            metadata['success'] = bool(clean_text(text))
            return text, metadata
        except Exception as exception:
            metadata['error'] = str(exception)

    if metadata['error'] is None:
        metadata['error'] = 'No OCR engine available'
    return '', metadata


def ocr_pdf(path, max_pages=DEFAULT_OCR_MAX_PAGES, zoom=2.0, workers=DEFAULT_OCR_WORKERS, languages=DEFAULT_OCR_LANGUAGES):
    return ocr_pdf_with_metadata(path, max_pages=max_pages, zoom=zoom, workers=workers, languages=languages)[0]


def ocr_pdf_with_metadata(path, max_pages=DEFAULT_OCR_MAX_PAGES, zoom=2.0, workers=DEFAULT_OCR_WORKERS, languages=DEFAULT_OCR_LANGUAGES):
    """OCR a scanned PDF by rendering pages with PyMuPDF, if available."""
    metadata = {
        'engine': 'tesseract' if HAS_TESSERACT else ('paddleocr' if HAS_PADDLEOCR else None),
        'engine_version': None,
        'languages_requested': parse_ocr_languages(languages),
        'max_pages': max_pages,
        'workers': max(1, workers),
        'pages_total': 0,
        'pages_attempted': 0,
        'pages_succeeded': 0,
        'pages_failed': 0,
        'page_errors': [],
        'success_rate': 0.0
    }
    if not HAS_PYMUPDF:
        metadata['page_errors'].append({'error': 'PyMuPDF not available'})
        return '', metadata
    if HAS_TESSERACT:
        try:
            metadata['engine_version'] = str(pytesseract.get_tesseract_version())
        except Exception:
            metadata['engine_version'] = None

    doc = None
    try:
        doc = fitz.open(path)
        metadata['pages_total'] = len(doc)
        total_pages = min(len(doc), max(1, max_pages))

        page_texts = {}
        for page_index in range(total_pages):
            metadata['pages_attempted'] += 1
            try:
                page = doc.load_page(page_index)
                pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
                image_bytes = pix.tobytes("png")
                del pix
                page_text, page_meta = ocr_image_with_metadata(image_bytes, languages=languages)
                if page_text:
                    page_texts[page_index] = page_text
                    metadata['pages_succeeded'] += 1
                else:
                    metadata['pages_failed'] += 1
                    metadata['page_errors'].append({
                        'page': page_index + 1,
                        'error': page_meta.get('error') or 'empty OCR result'
                    })
            except Exception as exception:
                metadata['pages_failed'] += 1
                metadata['page_errors'].append({'page': page_index + 1, 'error': str(exception)})

        metadata['success_rate'] = metadata['pages_succeeded'] / max(1, metadata['pages_attempted'])
        return '\n'.join(page_texts[index] for index in sorted(page_texts)), metadata
    except Exception as exception:
        metadata['page_errors'].append({'error': str(exception)})
        return '', metadata
    finally:
        if doc is not None:
            doc.close()

def ocr_file(path, max_pages=DEFAULT_OCR_MAX_PAGES, workers=DEFAULT_OCR_WORKERS, languages=DEFAULT_OCR_LANGUAGES):
    return ocr_file_with_metadata(path, max_pages=max_pages, workers=workers, languages=languages)[0]


def ocr_file_with_metadata(path, max_pages=DEFAULT_OCR_MAX_PAGES, workers=DEFAULT_OCR_WORKERS, languages=DEFAULT_OCR_LANGUAGES):
    lowered = str(path).lower()
    if lowered.endswith('.pdf'):
        return ocr_pdf_with_metadata(path, max_pages=max_pages, workers=workers, languages=languages)
    if lowered.endswith(('.png', '.jpg', '.jpeg', '.tif', '.tiff', '.bmp', '.gif', '.webp')):
        text, metadata = ocr_image_with_metadata(path, languages=languages)
        metadata.update({
            'pages_total': 1,
            'pages_attempted': 1,
            'pages_succeeded': 1 if metadata.get('success') else 0,
            'pages_failed': 0 if metadata.get('success') else 1,
            'success_rate': 1.0 if metadata.get('success') else 0.0
        })
        return text, metadata
    return '', {'error': 'unsupported OCR file type', 'success_rate': 0.0}


# === pgvector helpers ===

def _vec_literal(vec):
    # convert array-like to pgvector literal string: [0.1,0.2,...]
    try:
        if HAS_NUMPY and hasattr(vec, 'tolist'):
            arr = vec.tolist()
        else:
            arr = list(vec)
        return '[' + ','.join(str(float(x)) for x in arr) + ']'
    except Exception:
        return None


def validate_sql_identifier(identifier):
    """Allow only a simple PostgreSQL identifier supplied by configuration."""
    if not identifier or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', identifier):
        raise ValueError('invalid PostgreSQL table name')
    return identifier


def _verify_pgvector_chunk_table(cur, table, dim):
    cur.execute("SELECT to_regclass(%s)", (table,))
    if cur.fetchone()[0] is None:
        raise RuntimeError(f"pgvector table '{table}' is missing; run database migrations before analysis")

    cur.execute("""
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = %s
          AND column_name IN ('document_id', 'chunk_index', 'chunk_hash', 'chunk_text', 'embedding')
        GROUP BY table_name
        HAVING COUNT(*) = 5
    """, (table,))
    if cur.fetchone() is None:
        raise RuntimeError(f"pgvector table '{table}' does not have the expected chunk schema")


def store_doc_embedding_pg(pg_uri, table, doc_id, text, embedding):
    """Store or update a single document-level embedding in Postgres using pgvector."""
    if not HAS_PSYCOPG:
        return {'ok': False, 'error': 'psycopg/psycopg2 not available'}
    lit = _vec_literal(embedding)
    if lit is None:
        return {'ok': False, 'error': 'invalid embedding'}
    try:
        table = validate_sql_identifier(table)
        conn = psycopg2.connect(pg_uri)
        cur = conn.cursor()
        dim = len(embedding)
        _verify_pgvector_chunk_table(cur, table, dim)
        # upsert
        cur.execute(f"""
            INSERT INTO {table} (document_id, chunk_index, chunk_hash, chunk_text, embedding)
            VALUES (%s, 0, %s, %s, %s::vector)
            ON CONFLICT (document_id, chunk_index) DO UPDATE SET
                chunk_hash = EXCLUDED.chunk_hash,
                chunk_text = EXCLUDED.chunk_text,
                embedding = EXCLUDED.embedding,
                created_at = NOW()
        """, (doc_id, hashlib.sha256(text.encode('utf-8')).hexdigest(), text, lit))
        conn.commit()
        cur.close()
        conn.close()
        return {'ok': True}
    except Exception as e:
        return {'ok': False, 'error': str(e)}


def store_semantic_chunks_pg(pg_uri, table, doc_id, chunks, embeddings):
    """Store sentence-boundary semantic chunks and their pgvector embeddings."""
    if not HAS_PSYCOPG:
        return {'ok': False, 'error': 'psycopg/psycopg2 not available'}
    if not chunks:
        return {'ok': False, 'error': 'no semantic chunks to store'}
    try:
        table = validate_sql_identifier(table)
        conn = psycopg2.connect(pg_uri)
        cur = conn.cursor()
        dim = len(embeddings[0])
        _verify_pgvector_chunk_table(cur, table, dim)

        rows = []
        for idx, chunk in enumerate(chunks):
            chunk_hash = hashlib.sha256(chunk.encode('utf-8')).hexdigest()
            rows.append((doc_id, idx, chunk_hash, chunk, _vec_literal(embeddings[idx])))

        cur.execute(f"DELETE FROM {table} WHERE document_id = %s", (doc_id,))
        execute_values(
            cur,
            f"""
            INSERT INTO {table} (document_id, chunk_index, chunk_hash, chunk_text, embedding)
            VALUES %s
            ON CONFLICT (document_id, chunk_index) DO UPDATE SET
                chunk_hash = EXCLUDED.chunk_hash,
                chunk_text = EXCLUDED.chunk_text,
                embedding = EXCLUDED.embedding,
                created_at = NOW()
            """,
            rows,
            template="(%s, %s, %s, %s, %s::vector)"
        )
        conn.commit()
        cur.close()
        conn.close()
        return {'ok': True, 'chunks': len(chunks), 'embedding_dim': dim}
    except Exception as e:
        return {'ok': False, 'error': str(e)}


def query_similar_docs_pg(pg_uri, table, embedding, k=5, current_doc_id=None, min_similarity=DEFAULT_SEMANTIC_MATCH_THRESHOLD):
    if not HAS_PSYCOPG:
        return {'ok': False, 'error': 'psycopg/psycopg2 not available'}
    lit = _vec_literal(embedding)
    if lit is None:
        return {'ok': False, 'error': 'invalid embedding'}
    conn = None
    cur = None
    try:
        table = validate_sql_identifier(table)
        conn = psycopg2.connect(pg_uri)
        cur = conn.cursor()
        if current_doc_id:
            cur.execute(f"""
                SELECT document_id, chunk_index, chunk_text, embedding <=> %s::vector AS distance
                FROM {table}
                WHERE document_id <> %s
                  AND (1 - (embedding <=> %s::vector)) >= %s
                ORDER BY distance
                LIMIT %s
            """, (lit, current_doc_id, lit, min_similarity, k))
        else:
            cur.execute(f"""
                SELECT document_id, chunk_index, chunk_text, embedding <=> %s::vector AS distance
                FROM {table}
                WHERE (1 - (embedding <=> %s::vector)) >= %s
                ORDER BY distance
                LIMIT %s
            """, (lit, lit, min_similarity, k))
        rows = cur.fetchall()
        return {'ok': True, 'rows': [
            {
                'document_id': r[0],
                'chunk_index': r[1],
                'text': r[2],
                'distance': float(r[3]),
                'similarity': max(0.0, min(1.0, 1.0 - float(r[3])))
            } for r in rows
        ]}
    except Exception as e:
        return {'ok': False, 'error': str(e)}
    finally:
        if cur is not None:
            cur.close()
        if conn is not None:
            conn.close()


def query_chunks_before_store_pg(pg_uri, table, doc_id, chunks, embeddings, k=5, min_similarity=DEFAULT_SEMANTIC_MATCH_THRESHOLD):
    matches = []
    grouped = {}
    query_errors = []

    for idx, embedding in enumerate(embeddings):
        qres = query_similar_docs_pg(
            pg_uri,
            table,
            embedding,
            k=k,
            current_doc_id=doc_id,
            min_similarity=min_similarity
        )
        if not qres.get('ok'):
            query_errors.append(qres.get('error', 'unknown pgvector query error'))
            continue

        for row in qres.get('rows', []):
            match = {
                'query_chunk_index': idx,
                'query_text': chunks[idx][:300],
                'source_document_id': row['document_id'],
                'source_chunk_index': row['chunk_index'],
                'source_text': row['text'][:300],
                'semantic_similarity': row['similarity'],
                'distance': row['distance']
            }
            matches.append(match)
            grouped.setdefault(row['document_id'], {
                'document_id': row['document_id'],
                'match_count': 0,
                'matched_query_chunks': set(),
                'max_similarity': 0.0,
                'avg_similarity_sum': 0.0
            })
            group = grouped[row['document_id']]
            group['match_count'] += 1
            group['matched_query_chunks'].add(idx)
            group['max_similarity'] = max(group['max_similarity'], row['similarity'])
            group['avg_similarity_sum'] += row['similarity']

    grouped_sources = []
    for group in grouped.values():
        match_count = group['match_count']
        grouped_sources.append({
            'document_id': group['document_id'],
            'match_count': match_count,
            'matched_query_chunks': len(group['matched_query_chunks']),
            'coverage': len(group['matched_query_chunks']) / max(1, len(chunks)),
            'max_similarity': group['max_similarity'],
            'avg_similarity': group['avg_similarity_sum'] / max(1, match_count)
        })
    grouped_sources.sort(key=lambda item: (item['coverage'], item['max_similarity']), reverse=True)

    matched_chunks = {match['query_chunk_index'] for match in matches}
    coverage = len(matched_chunks) / max(1, len(chunks))
    avg_similarity = sum(match['semantic_similarity'] for match in matches) / len(matches) if matches else 0.0
    external_score = int(round(min(1.0, coverage * 0.75 + avg_similarity * 0.25) * 100)) if matches else 0

    return {
        'ok': not query_errors,
        'errors': query_errors,
        'query_k': k,
        'min_similarity': min_similarity,
        'matches': matches[:50],
        'source_documents': grouped_sources[:20],
        'matched_chunks': len(matched_chunks),
        'total_chunks': len(chunks),
        'coverage': coverage,
        'avg_similarity': avg_similarity,
        'external_score': external_score
    }


# === high-level analyze ===

def analyze_text(text: str, model_name='all-MiniLM-L6-v2'):
    text = clean_text(text)
    if not text:
        return {
            'overallScore': 0,
            'aiScore': 0,
            'details': {
                'status': 'FAILED',
                'failedStep': 'extraction',
                'errorMessage': 'empty text'
            }
        }

    all_sentences = split_sentences(text)
    truncated = len(all_sentences) > MAX_SENTENCES
    sentences = all_sentences[:MAX_SENTENCES]
    if len(sentences) == 0:
        sentences = [text]

    fingerprint_pairs = compute_fingerprint_similarity(sentences)
    lexical_pairs = compute_lexical_similarity(sentences)
    semantic_info = compute_semantic_similarity(sentences, model_name=model_name)
    ai_tokens = detect_ai_tokens(text)

    overall, ai_score = score_from_findings(len(sentences), fingerprint_pairs, lexical_pairs, semantic_info, ai_tokens)

    def trunc(s):
        return (s[:200] + '...') if len(s) > 200 else s

    details = {
        'status': 'COMPLETED',
        'algorithm_version': 'external-pgvector-v1',
        'num_sentences': len(sentences),
        'total_sentences': len(all_sentences),
        'truncated': truncated,
        'top_fingerprint_matches': [{k: v for k, v in {'i':p['i'],'j':p['j'],'score':p['score'],'si':trunc(p['si']),'sj':trunc(p['sj'])}.items()} for p in fingerprint_pairs[:10]],
        'top_lexical_matches': [{k: v for k, v in {'i':p['i'],'j':p['j'],'score':p['score'],'si':trunc(p['si']),'sj':trunc(p['sj'])}.items()} for p in lexical_pairs[:10]],
        'semantic': {
            'avg_similarity': semantic_info.get('avg'),
            'top_pairs': semantic_info.get('pairs')[:10] if isinstance(semantic_info.get('pairs'), list) else []
        },
        'ai_keyword_indicator': ai_score,
        'ai_keyword_count': ai_tokens,
        'ai_indicator_method': 'explicit_keyword_indicator',
        'limitations': [
            'Similarity is currently measured between passages of the same document.',
            'The AI keyword indicator is not an AI-authorship detector.'
        ]
    }

    # attach embeddings if available
    if 'embeddings' in semantic_info:
        details['embedding_dim'] = len(semantic_info['embeddings'][0]) if len(semantic_info['embeddings'])>0 else None

    return {'overallScore': overall, 'aiScore': ai_score, 'details': details}


if __name__ == '__main__':
    try:
        import argparse
        with contextlib.redirect_stdout(sys.stderr):
            parser = argparse.ArgumentParser()
            parser.add_argument('--text', help='Text input (alternative to stdin)')
            parser.add_argument('--image', help='Path to image to OCR')
            parser.add_argument('--file', help='Path to original document for OCR fallback')
            parser.add_argument('--ocr', action='store_true', help='Run OCR on --file and append/replace weak extracted text')
            parser.add_argument('--pg-uri', help='Postgres URI (for pgvector)')
            parser.add_argument('--pg-table', help='Postgres table name for embeddings')
            parser.add_argument('--store-doc', help='Document id to store in pg table')
            parser.add_argument('--current-doc', help='Current document id to exclude from pgvector results')
            parser.add_argument('--query-k', type=int, default=0, help='If >0, query pg table for k nearest chunks')
            parser.add_argument('--model', default='all-MiniLM-L6-v2', help='SentenceTransformer model')
            parser.add_argument('--chunk-max-chars', type=int, default=DEFAULT_CHUNK_MAX_CHARS, help='Maximum characters per semantic chunk')
            parser.add_argument('--chunk-overlap-sentences', type=int, default=DEFAULT_CHUNK_OVERLAP_SENTENCES, help='Sentence overlap between semantic chunks')
            parser.add_argument('--ocr-max-pages', type=int, default=DEFAULT_OCR_MAX_PAGES, help='Maximum PDF pages to OCR')
            parser.add_argument('--ocr-workers', type=int, default=DEFAULT_OCR_WORKERS, help='Parallel OCR workers for PDF pages')
            parser.add_argument('--ocr-languages', default=DEFAULT_OCR_LANGUAGES, help='Comma-separated Tesseract language priorities, e.g. fra+lin+swa+eng,fra+eng,fra,eng')
            args = parser.parse_args()

            body = ''
            try:
                if not sys.stdin.isatty():
                    body = sys.stdin.read()
            except Exception:
                body = ''

            text = None
            ocr_metadata = None
            if args.text:
                text = args.text
            elif args.image:
                ocr_text, ocr_metadata = ocr_file_with_metadata(args.image, languages=args.ocr_languages)
                text = ocr_text
            elif args.file and args.ocr:
                base_text = ''
                if body:
                    try:
                        parsed = json.loads(body)
                        base_text = parsed.get('text', '') if isinstance(parsed, dict) else str(parsed)
                    except Exception:
                        base_text = body
                ocr_text, ocr_metadata = ocr_file_with_metadata(
                    args.file,
                    max_pages=max(1, args.ocr_max_pages),
                    workers=max(1, args.ocr_workers),
                    languages=args.ocr_languages
                )
                text = ocr_text if text_quality_score(ocr_text) > text_quality_score(base_text) else base_text
            elif body:
                try:
                    parsed = json.loads(body)
                    if isinstance(parsed, dict) and 'text' in parsed:
                        text = parsed['text']
                    elif isinstance(parsed, str):
                        text = parsed
                    else:
                        text = body
                except Exception:
                    text = body

            if not text or not str(text).strip():
                result = {
                    'overallScore': 0,
                    'aiScore': 0,
                    'details': {
                        'status': 'FAILED',
                        'failedStep': 'extraction',
                        'errorMessage': 'empty input'
                    }
                }
            else:
                result = analyze_text(str(text), model_name=args.model)
                if ocr_metadata is not None:
                    result['details']['ocr'] = ocr_metadata
                    result['details']['text_quality'] = {
                        'selected_text_score': text_quality_score(text),
                        'ocr_text_score': text_quality_score(ocr_text if 'ocr_text' in locals() else ''),
                        'min_text_length': DEFAULT_MIN_TEXT_LENGTH,
                        'min_ocr_success_rate': DEFAULT_OCR_MIN_SUCCESS_RATE
                    }
                    if len(clean_text(text)) < DEFAULT_MIN_TEXT_LENGTH:
                        result['overallScore'] = 0
                        result['details']['status'] = 'FAILED'
                        result['details']['failedStep'] = 'extraction'
                        result['details']['errorMessage'] = 'extracted text is too short'
                    elif ocr_metadata.get('pages_attempted', 0) > 0 and ocr_metadata.get('success_rate', 0.0) < DEFAULT_OCR_MIN_SUCCESS_RATE:
                        result['details']['status'] = 'DEGRADED'
                        result['details']['failedStep'] = 'OCR'
                        result['details']['errorMessage'] = 'OCR coverage below configured threshold'

                if args.pg_uri and args.pg_table and HAS_SBERT and args.store_doc:
                    try:
                        model = load_sentence_model(args.model)
                        chunks = semantic_chunks(
                            text,
                            max_chars=max(200, args.chunk_max_chars),
                            overlap_sentences=max(0, args.chunk_overlap_sentences)
                        )
                        embeddings = model.encode(chunks, convert_to_numpy=HAS_NUMPY)
                        if args.query_k and args.query_k > 0:
                            query_res = query_chunks_before_store_pg(
                                args.pg_uri,
                                args.pg_table,
                                args.current_doc or args.store_doc,
                                chunks,
                                embeddings,
                                k=max(1, args.query_k),
                                min_similarity=DEFAULT_SEMANTIC_MATCH_THRESHOLD
                            )
                            result['pg_query'] = query_res
                            result['overallScore'] = query_res.get('external_score', 0)
                            result['details']['external_similarity'] = query_res
                            result['details']['limitations'] = [
                                'The plagiarism score is based on external source chunks already present in pgvector.',
                                'Internal repeated passages are reported separately and are not treated as plagiarism evidence.'
                            ]

                        store_res = store_semantic_chunks_pg(args.pg_uri, args.pg_table, args.store_doc, chunks, embeddings)
                        result['pg_store'] = store_res
                        result['details']['semantic_chunks'] = len(chunks)
                        result['details']['pg_store'] = store_res
                        if not store_res.get('ok'):
                            result['details']['status'] = 'DEGRADED'
                            result['details']['failedStep'] = 'pgvector'
                            result['details']['errorMessage'] = store_res.get('error')
                    except Exception as e:
                        result['pg_store'] = {'ok': False, 'error': str(e)}
                        result['details']['status'] = 'DEGRADED'
                        result['details']['failedStep'] = 'pgvector'
                        result['details']['errorMessage'] = str(e)

                if args.pg_uri and args.pg_table and HAS_SBERT and args.query_k and args.query_k>0:
                    # This document-level query is kept for direct CLI use when --store-doc is absent.
                    if not args.store_doc:
                        try:
                            model = load_sentence_model(args.model)
                            qemb = model.encode([text], convert_to_numpy=HAS_NUMPY)[0]
                            qres = query_similar_docs_pg(args.pg_uri, args.pg_table, qemb, k=args.query_k, current_doc_id=args.current_doc)
                            result['pg_query'] = qres
                        except Exception as e:
                            result['pg_query'] = {'ok': False, 'error': str(e)}

        sys.stdout.write(json.dumps(result, ensure_ascii=True))
        sys.stdout.write("\n")
    except Exception as e:
        sys.stdout.write(json.dumps({
            'overallScore': 0,
            'aiScore': 0,
            'details': {
                'status': 'FAILED',
                'failedStep': 'analysis',
                'errorMessage': clean_text(str(e))
            }
        }, ensure_ascii=True))
        sys.stdout.write("\n")
        sys.exit(1)
