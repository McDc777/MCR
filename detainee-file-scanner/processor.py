# NO EXTERNAL NETWORK CALLS OR TELEMETRY

import re
import pandas as pd
import pdfplumber
from docx import Document
import io

QUESTIONS = [
    {
        "id": 1,
        "question": "Does the Detainee have a history of violence?",
        "keywords": ["violence", "assault", "battery", "attacked", "stabbed", "aggravated assault", "violent behaviour", "convicted of assault"],
    },
    {
        "id": 2,
        "question": "Does the Detainee have a history of sexual assault?",
        "keywords": ["sexual assault", "rape", "sexual abuse", "molestation", "sexual offence"],
    },
    {
        "id": 3,
        "question": "Does the Detainee have a history of escaping custody?",
        "keywords": ["escaped", "absconded", "absconding", "escaped custody", "absconder", "walked away"],
    },
    {
        "id": 4,
        "question": "Does the Detainee have a history of involvement in Organised Crime or Outlaw Motorcycle Gangs?",
        "keywords": ["organised crime", "organized crime", "outlaw motorcycle", "bikie", "gang member", "gang affiliation"],
    },
    {
        "id": 5,
        "question": "Does the Detainee have a history of or conviction for drug use?",
        "keywords": ["drug", "drugs", "possession", "trafficking", "methamphetamine", "heroin", "cocaine", "drug conviction"],
    },
    {
        "id": 6,
        "question": "Is the Detainee from a correctional facility or was transferred from another detention centre?",
        "keywords": ["transferred from", "transferred", "from", "released from prison", "received from correctional facility"],
    },
    {
        "id": 7,
        "question": "Does the Detainee have a history of self-harm or made threats of self-harm?",
        "keywords": ["self-harm", "suicide attempt", "suicidal ideation", "threatened self-harm", "attempted suicide"],
    },
]

NEGATION_TOKENS = ["no", "none", "denies", "not", "without", "no history"]

def extract_text_from_pdf(file):
    with pdfplumber.open(file) as pdf:
        return "".join(page.extract_text() for page in pdf.pages if page.extract_text())

def extract_text_from_docx(file):
    doc = Document(file)
    return "\n".join(para.text for para in doc.paragraphs)

def extract_text_from_excel(file):
    try:
        return pd.read_excel(file, engine='openpyxl').to_string()
    except Exception:
        return pd.read_excel(file, engine='xlrd').to_string()

def extract_text_from_csv(file):
    return pd.read_csv(io.StringIO(file.read().decode('utf-8'))).to_string()

def extract_text_from_txt(file):
    return file.read().decode("utf-8")

def extract_text(file):
    try:
        content = file.read()
        file.seek(0)
        if file.name.endswith(".pdf"):
            return extract_text_from_pdf(io.BytesIO(content))
        elif file.name.endswith(".docx"):
            return extract_text_from_docx(io.BytesIO(content))
        elif file.name.endswith((".xlsx", ".xls")):
            return extract_text_from_excel(io.BytesIO(content))
        elif file.name.endswith(".csv"):
            return extract_text_from_csv(file)
        elif file.name.endswith(".txt"):
            return extract_text_from_txt(file)
    except Exception:
        return ""
    return ""

def get_finding_snippet(sentence, keyword):
    words = sentence.split()
    try:
        # Find the start of the keyword phrase in the sentence words
        kw_words = keyword.split()
        for i in range(len(words) - len(kw_words) + 1):
            if words[i:i+len(kw_words)] == kw_words:
                # Found it, now grab a snippet
                start = max(0, i - 3)
                end = min(len(words), i + len(kw_words) + 3)
                snippet = " ".join(words[start:end])
                return " ".join(snippet.split()[:10]) # Ensure max 10 words
    except:
        pass
    # Fallback
    return " ".join(sentence.split()[:10])


def find_answers(text):
    final_answers = ["Nil information was provided"] * len(QUESTIONS)
    text_lower = text.lower()
    sentences = re.split(r'(?<=[.!?])\s+', text_lower)

    for i, q in enumerate(QUESTIONS):
        findings = []
        for sent in sentences:
            for keyword in q['keywords']:
                match = re.search(r'\b' + re.escape(keyword) + r'\b', sent)
                if match:
                    # Determine finding type
                    is_negated = any(re.search(r'\b' + neg + r'\b', sent[:match.start()]) for neg in NEGATION_TOKENS)
                    is_implied = "implied" in sent or "imply" in sent

                    if is_implied:
                        finding_type = "implied"
                    elif is_negated:
                        finding_type = "explicit_neg"
                    else:
                        finding_type = "explicit_pos"

                    # Extract year
                    year_match = re.search(r'\b(20\d{2})\b', sent)
                    year = int(year_match.group(1)) if year_match else 0

                    snippet = get_finding_snippet(sent, keyword)
                    findings.append({"type": finding_type, "year": year, "text": snippet})

        if not findings:
            continue

        # Prioritize findings
        findings.sort(key=lambda x: (
            1 if x['type'] == 'explicit_pos' else 2 if x['type'] == 'explicit_neg' else 3,
            -x['year'] # Sort by year descending
        ))

        best_finding = findings[0]
        answer_text = best_finding['text']
        if best_finding['type'] == 'implied':
            answer_text = f"Implied: {answer_text}"

        final_answers[i] = answer_text.capitalize()

    return final_answers
