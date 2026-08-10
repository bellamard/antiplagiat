#!/usr/bin/env python3
"""
Advanced analyzer pipeline:
- text cleaning
- sentence segmentation
- fingerprint (shingle) creation
- similarity calculations (rapidfuzz)
- paraphrase detection using sentence-transformers embeddings + semantic similarity
- generate JSON output with overallScore, aiScore and details

Requires (recommended):
  - sentence-transformers
  - transformers
  - scikit-learn
  - spacy
  - numpy
  - rapidfuzz

Script is robust: falls back to lightweight heuristics if heavy libs are missing.
"""

import sys
import json
import re
from collections import Counter

# try heavy libraries, fall back gracefully
try:
    import numpy as np
    from rapidfuzz import fuzz
    try:
        from sentence_transformers import SentenceTransformer
        HAS_SBERT = True
    except Exception:
        HAS_SBERT = False
    try:
        import spacy
        HAS_SPACY = True
        # try to load small model; if not present, will fallback to simple split
        try:
            nlp = spacy.load("en_core_web_sm")
        except Exception:
            # don't auto-download model here; fallback
            nlp = None
    except Exception:
        HAS_SPACY = False
        nlp = None
except Exception:
    np = None
    fuzz = None
    SentenceTransformer = None
    HAS_SPACY = False
    nlp = None
    HAS_SBERT = False


def clean_text(text: str) -> str:
    # normalize whitespace, remove control chars, strip
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"[\x00-\x1f\x7f]+", "", text)
    return text.strip()


def split_sentences(text: str):
    if HAS_SPACY and nlp is not None:
        doc = nlp(text)
        return [sent.text.strip() for sent in doc.sents if sent.text.strip()]
    # fallback: naive split by punctuation
    cand = re.split(r'(?<=[.!?;])\s+', text)
    return [s.strip() for s in cand if s.strip()]


def shingles(sentence: str, k=5):
    # character k-grams
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
    # use rapidfuzz token_set_ratio if available
    n = len(sentences)
    pairs = []
    if fuzz is None:
        return pairs
    for i in range(n):
        for j in range(i+1, n):
            score = fuzz.token_set_ratio(sentences[i], sentences[j]) / 100.0
            if score > 0.6:
                pairs.append({'i': i, 'j': j, 'score': score, 'si': sentences[i], 'sj': sentences[j]})
    pairs.sort(key=lambda x: x['score'], reverse=True)
    return pairs


def compute_semantic_similarity(sentences, model_name='all-MiniLM-L6-v2', threshold=0.75):
    if not HAS_SBERT:
        return {'pairs': [], 'avg': None}
    try:
        model = SentenceTransformer(model_name)
        embeddings = model.encode(sentences, convert_to_numpy=True)
        # normalize
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
        return {'pairs': pairs, 'avg': avg}
    except Exception as e:
        return {'pairs': [], 'avg': None, 'error': str(e)}


def detect_ai_tokens(text: str):
    ai_tokens = ['gpt', 'chatgpt', 'openai', 'llm', 'generated', 'ai', 'auto-generated']
    count = sum(1 for t in ai_tokens if t in text.lower())
    return count


def score_from_findings(num_sentences:int, fingerprint_pairs, lexical_pairs, semantic_info, ai_token_count:int):
    # base score proportional to suspicious findings
    fp_score = min(100, len(fingerprint_pairs) * 15)
    lex_score = min(100, len(lexical_pairs) * 10)
    sem_score = 0
    if semantic_info.get('avg') is not None:
        # average semantic similarity mapped to 0-100
        sem_score = int(semantic_info['avg'] * 100)
    ai_score = min(100, ai_token_count * 25)

    # overall: weighted
    overall = int(min(100, fp_score * 0.4 + lex_score * 0.2 + sem_score * 0.3 + ai_score * 0.1))
    return overall, ai_score


def analyze_text(text: str):
    text = clean_text(text)
    if not text:
        return {'overallScore': 0, 'aiScore': 0, 'details': {'error': 'empty'}}

    sentences = split_sentences(text)
    if len(sentences) == 0:
        sentences = [text]

    # fingerprint and lexical
    fingerprint_pairs = compute_fingerprint_similarity(sentences)
    lexical_pairs = compute_lexical_similarity(sentences)

    # semantic (embeddings)
    semantic_info = compute_semantic_similarity(sentences)

    # ai tokens
    ai_tokens = detect_ai_tokens(text)

    overall, ai_score = score_from_findings(len(sentences), fingerprint_pairs, lexical_pairs, semantic_info, ai_tokens)

    # prepare details (truncate long texts)
    def trunc(s):
        return (s[:200] + '...') if len(s) > 200 else s

    details = {
        'num_sentences': len(sentences),
        'top_fingerprint_matches': [{k: v for k, v in {'i':p['i'],'j':p['j'],'score':p['score'],'si':trunc(p['si']),'sj':trunc(p['sj'])}.items()} for p in fingerprint_pairs[:10]],
        'top_lexical_matches': [{k: v for k, v in {'i':p['i'],'j':p['j'],'score':p['score'],'si':trunc(p['si']),'sj':trunc(p['sj'])}.items()} for p in lexical_pairs[:10]],
        'semantic': {
            'avg_similarity': semantic_info.get('avg'),
            'top_pairs': semantic_info.get('pairs')[:10] if isinstance(semantic_info.get('pairs'), list) else []
        },
        'ai_token_count': ai_tokens
    }

    return {'overallScore': overall, 'aiScore': ai_score, 'details': details}


if __name__ == '__main__':
    try:
        text = sys.stdin.read()
        result = analyze_text(text)
        print(json.dumps(result))
    except Exception as e:
        print(json.dumps({'overallScore': 0, 'aiScore': 0, 'details': {'error': str(e)}}))
