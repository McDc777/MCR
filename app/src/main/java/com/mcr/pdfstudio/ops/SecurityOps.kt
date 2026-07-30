package com.mcr.pdfstudio.ops

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy

/** What a reader is allowed to do with an encrypted document. */
data class Permissions(
    val canPrint: Boolean = true,
    val canModify: Boolean = true,
    val canExtractContent: Boolean = true,
    val canModifyAnnotations: Boolean = true,
    val canFillInForm: Boolean = true,
    val canAssembleDocument: Boolean = true,
    val canPrintHighQuality: Boolean = true,
    val canExtractForAccessibility: Boolean = true,
) {
    fun toAccessPermission(): AccessPermission = AccessPermission().also {
        it.setCanPrint(canPrint)
        it.setCanModify(canModify)
        it.setCanExtractContent(canExtractContent)
        it.setCanModifyAnnotations(canModifyAnnotations)
        it.setCanFillInForm(canFillInForm)
        it.setCanAssembleDocument(canAssembleDocument)
        it.setCanPrintDegraded(canPrintHighQuality)
        it.setCanExtractForAccessibility(canExtractForAccessibility)
    }
}

object SecurityOps {

    /**
     * Encrypts with AES-256. [userPassword] is needed to open the file;
     * [ownerPassword] bypasses the permission restrictions.
     */
    fun protect(
        doc: PDDocument,
        userPassword: String,
        ownerPassword: String = userPassword,
        permissions: Permissions = Permissions(),
    ) {
        val policy = StandardProtectionPolicy(
            ownerPassword.ifBlank { userPassword },
            userPassword,
            permissions.toAccessPermission()
        )
        policy.encryptionKeyLength = 256
        policy.permissions = permissions.toAccessPermission()
        doc.protect(policy)
    }

    /** Strips encryption. Only works if the document was opened successfully. */
    fun removeProtection(doc: PDDocument) {
        doc.isAllSecurityToBeRemoved = true
    }

    fun isEncrypted(doc: PDDocument): Boolean = doc.isEncrypted

    fun describe(doc: PDDocument): String {
        if (!doc.isEncrypted) return "Not password protected"
        val p = doc.currentAccessPermission
        val allowed = buildList {
            if (p.canPrint()) add("print")
            if (p.canModify()) add("modify")
            if (p.canExtractContent()) add("copy text")
            if (p.canFillInForm()) add("fill forms")
            if (p.canModifyAnnotations()) add("annotate")
            if (p.canAssembleDocument()) add("assemble")
        }
        return if (allowed.isEmpty()) {
            "Encrypted · no permissions granted"
        } else {
            "Encrypted · allows " + allowed.joinToString(", ")
        }
    }
}
