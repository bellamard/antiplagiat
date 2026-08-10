#!/usr/bin/env python3
import sys
import json

def analyze_text(text):
    length = len(text)
    # naive plagiarism heuristic: longer text -> higher overall score
    overall = min(100, length // 10)
    # naive ai detection: presence of certain tokens
    ai_tokens = ['gpt', 'chatgpt', 'openai', 'llm', 'generated', 'AI', 'ai']
    ai_count = sum(1 for t in ai_tokens if t in text.lower())
    ai_score = min(100, ai_count * 20)
    details = {'length': length, 'ai_count': ai_count}
    return {'overallScore': overall, 'aiScore': ai_score, 'details': details}

if __name__ == '__main__':
    try:
        text = sys.stdin.read()
        result = analyze_text(text)
        print(json.dumps(result))
    except Exception as e:
        print(json.dumps({'overallScore':0,'aiScore':0,'details':{'error': str(e)}}))
