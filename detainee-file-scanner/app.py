# NO EXTERNAL NETWORK CALLS OR TELEMETRY

import streamlit as st
import processor
import os

st.title("Detainee File Scanner")

st.markdown("""
This tool analyzes uploaded files for specific information.
All processing is done locally in your browser. No data is sent to any server.
""")

uploaded_files = st.file_uploader(
    "Upload files",
    type=["pdf", "docx", "xlsx", "xls", "csv", "txt"],
    accept_multiple_files=True
)

if st.button("Process Files"):
    if uploaded_files:
        all_text = ""
        for file in uploaded_files:
            all_text += processor.extract_text(file) + "\n"

        if all_text.strip():
            st.subheader("Findings")
            answers = processor.find_answers(all_text)
            for i, q in enumerate(processor.QUESTIONS):
                st.write(f"**{q['question']}**")
                st.write(answers[i])

            st.subheader("Bottom Answers")
            question_map = {
                1: "History of violence?",
                2: "History of sexual assault?",
                3: "History of escaping custody?",
                4: "History of involvement in organised crime?",
                5: "History of drug use?",
                6: "From correctional facility?",
                7: "History of self harm?",
            }
            for i, q in enumerate(processor.QUESTIONS):
                question_short = question_map.get(q['id'], q['question'])
                st.write(f"{i+1}. {question_short} — {answers[i]}")
        else:
            st.warning("Could not extract any text from the uploaded files.")
    else:
        st.warning("Please upload at least one file.")

if os.path.exists("detainee-file-scanner.zip"):
    with open("detainee-file-scanner.zip", "rb") as fp:
        st.download_button(
            label="Download Application Zip",
            data=fp,
            file_name="detainee-file-scanner.zip",
            mime="application/zip"
        )
