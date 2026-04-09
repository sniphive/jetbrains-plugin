package com.sniphive.idea.completion

import com.google.gson.JsonParser
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.crypto.AESCrypto
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.RSACrypto
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.services.SnipHiveAuthService
import com.sniphive.idea.services.SnipHiveApiClient
import com.sniphive.idea.services.SecureCredentialStorage

/**
 * Code completion provider for SnipHive snippets.
 *
 * This provider integrates SnipHive snippets into the IDE's code completion mechanism.
 * When the user types in an editor, matching snippets are shown as completion suggestions.
 *
 * Features:
 * - Provides snippets as completion suggestions based on title matching
 * - Supports filtering by language (shows relevant snippets for current file type)
 * - Supports E2EE: decrypts encrypted snippet content before insertion
 * - Shows snippet preview in completion popup
 * - Inserts full snippet content at cursor position on selection
 *
 * Security Note:
 * - No credentials or tokens logged or exposed
 * - E2EE decryption happens client-side only
 * - Authentication check performed before fetching snippets
 * - Private keys never leave the device
 *
 * @see Snippet
 * @see E2EECryptoService
 * @see EnvelopeEncryption
 */
class SnippetCompletionProvider : CompletionContributor() {

    companion object {
        private val LOG = Logger.getInstance(SnippetCompletionProvider::class.java)

        // API endpoint for fetching snippets
        private const val FETCH_SNIPPETS_ENDPOINT = "/snippets"

        // Minimum prefix length for triggering completion (to avoid too many matches)
        private const val MIN_PREFIX_LENGTH = 2

        // Maximum number of snippets to show in completion
        private const val MAX_SUGGESTIONS = 20
    }

    /**
     * Initialize the completion provider.
     *
     * Registers completion patterns for all file types.
     * The completion is triggered on any text element (universal completion).
     */
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    resultSet: CompletionResultSet
                ) {
                    val project = parameters.originalFile.project
                    val editor = parameters.editor
                    val prefix = findPrefix(parameters) ?: return

                    // Only trigger completion if prefix is long enough
                    if (prefix.length < MIN_PREFIX_LENGTH) {
                        return
                    }

                    LOG.debug("Snippet completion triggered with prefix: '$prefix'")

                    // Fetch and provide completion suggestions
                    provideSnippetCompletions(project, editor, prefix, resultSet, parameters)
                }
            }
        )
    }

    /**
     * Find the text prefix at the current caret position.
     *
     * This extracts the word or identifier that the user is currently typing.
     *
     * @param parameters The completion parameters
     * @return The prefix text, or null if no suitable prefix found
     */
    private fun findPrefix(parameters: CompletionParameters): String? {
        val editor = parameters.editor
        val offset = parameters.offset
        val document = editor.document
        val text = document.charsSequence

        // Find the start of the prefix (go backwards until we hit a non-word character)
        var start = offset - 1
        while (start >= 0 && isPrefixChar(text[start])) {
            start--
        }

        // Extract the prefix
        return if (start < offset - 1) {
            text.subSequence(start + 1, offset).toString()
        } else {
            null
        }
    }

    /**
     * Check if a character is part of a prefix (word character).
     *
     * @param char The character to check
     * @return true if the character is a letter, digit, or underscore
     */
    private fun isPrefixChar(char: Char): Boolean {
        return char.isLetterOrDigit() || char == '_'
    }

    /**
     * Provide snippet completions based on the prefix.
     *
     * This method:
     * 1. Checks if user is authenticated
     * 2. Fetches snippets from the API
     * 3. Filters snippets by prefix and language
     * 4. Creates lookup elements for matching snippets
     * 5. Adds them to the result set
     *
     * @param project The current project
     * @param editor The current editor
     * @param prefix The prefix to match
     * @param resultSet The result set to add completions to
     * @param parameters The completion parameters
     */
    private fun provideSnippetCompletions(
        project: Project,
        editor: Editor,
        prefix: String,
        resultSet: CompletionResultSet,
        parameters: CompletionParameters
    ) {
        val settings = SnipHiveSettings.getInstance(project)
        val apiUrl = settings.getApiUrl()

        if (apiUrl.isEmpty()) {
            LOG.debug("API URL not configured, skipping snippet completion")
            return
        }

        val authService = SnipHiveAuthService.getInstance()
        if (!authService.isCurrentAuthenticated(project)) {
            LOG.debug("User not authenticated, skipping snippet completion")
            return
        }

        val email = settings.getUserEmail()
        val token = authService.getAuthToken(project, email)

        if (token == null) {
            LOG.warn("Authentication token not found for user: $email")
            return
        }

        // Get current file language for filtering
        val currentLanguage = detectFileLanguage(parameters.originalFile)

        // Fetch snippets in background to avoid blocking the UI
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = SnipHiveApiClient.getInstance()

                // Build query parameters
                val queryParams = buildMap {
                    put("search", prefix)
                    put("limit", MAX_SUGGESTIONS.toString())
                    if (!currentLanguage.isNullOrEmpty()) {
                        put("language", currentLanguage)
                    }
                }

                // Fetch snippets from API
                val response = apiClient.get<SnippetsResponse>(
                    apiUrl = apiUrl,
                    endpoint = FETCH_SNIPPETS_ENDPOINT,
                    token = token,
                    queryParams = queryParams,
                    responseType = SnippetsResponse::class.java
                )

                if (response.success && response.data != null) {
                    val snippets = response.data.data

                    LOG.debug("Fetched ${snippets.size} snippets for prefix: '$prefix'")

                    // Add completions on EDT
                    ApplicationManager.getApplication().invokeLater {
                        addSnippetLookupElements(
                            snippets,
                            project,
                            prefix,
                            resultSet,
                            parameters
                        )
                    }
                } else {
                    LOG.warn("Failed to fetch snippets: ${response.error}")
                }
            } catch (e: Exception) {
                LOG.error("Error fetching snippets for completion", e)
            }
        }
    }

    /**
     * Detect the programming language of the current file.
     *
     * This maps file extensions to SnipHive language names.
     *
     * @param file The current file
     * @return The language name or null if language could not be detected
     */
    private fun detectFileLanguage(file: PsiFile): String? {
        val extension = file.virtualFile?.extension?.lowercase() ?: return null

        return when (extension) {
            "php" -> "PHP"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            "jsx" -> "JavaScript"
            "tsx" -> "TypeScript"
            "py" -> "Python"
            "java" -> "Java"
            "kt" -> "Kotlin"
            "go" -> "Go"
            "rs" -> "Rust"
            "c", "cpp", "cc", "cxx", "h", "hpp" -> "C/C++"
            "cs" -> "C#"
            "swift" -> "Swift"
            "rb" -> "Ruby"
            "sql" -> "SQL"
            "sh", "bash" -> "Shell"
            "yaml", "yml" -> "YAML"
            "json" -> "JSON"
            "xml" -> "XML"
            "html" -> "HTML"
            "css" -> "CSS"
            "scss" -> "SCSS"
            "sass" -> "Sass"
            "md" -> "Markdown"
            else -> null
        }
    }

    /**
     * Add lookup elements for snippets to the result set.
     *
     * This method creates LookupElement instances for each snippet,
     * with appropriate icons, text, and insert handlers.
     *
     * @param snippets The list of snippets to add
     * @param project The current project
     * @param prefix The prefix used for matching
     * @param resultSet The result set to add completions to
     * @param parameters The completion parameters
     */
    private fun addSnippetLookupElements(
        snippets: List<Snippet>,
        project: Project,
        prefix: String,
        resultSet: CompletionResultSet,
        parameters: CompletionParameters
    ) {
        val startOffset = findPrefixStartOffset(parameters) ?: return

        for (snippet in snippets) {
            // Filter out archived snippets
            if (snippet.isArchived()) {
                continue
            }

            // Filter by prefix match (case-insensitive)
            if (!snippet.title.contains(prefix, ignoreCase = true)) {
                continue
            }

            val element = createSnippetLookupElement(
                snippet,
                project,
                startOffset,
                prefix
            )

            element?.let { resultSet.addElement(it) }
        }
    }

    /**
     * Find the start offset of the prefix in the document.
     *
     * @param parameters The completion parameters
     * @return The start offset, or null if not found
     */
    private fun findPrefixStartOffset(parameters: CompletionParameters): Int? {
        val editor = parameters.editor
        val offset = parameters.offset
        val document = editor.document
        val text = document.charsSequence

        var start = offset - 1
        while (start >= 0 && isPrefixChar(text[start])) {
            start--
        }

        return if (start < offset - 1) {
            start + 1
        } else {
            null
        }
    }

    /**
     * Create a lookup element for a snippet.
     *
     * @param snippet The snippet to create an element for
     * @param project The current project
     * @param startOffset The start offset of the prefix
     * @param prefix The prefix text
     * @return The lookup element, or null if creation failed
     */
    private fun createSnippetLookupElement(
        snippet: Snippet,
        project: Project,
        startOffset: Int,
        prefix: String
    ): LookupElement? {
        val builder = LookupElementBuilder.create(snippet, snippet.title)
            .withPresentableText(snippet.title)
            .withTailText(" (${snippet.getDisplayLanguage()})")
            .withTypeText("SnipHive")
            .withInsertHandler { context, item ->
                insertSnippet(context, snippet, project, startOffset, prefix)
            }

        // Add a preview of the snippet content (first line)
        val previewText = snippet.content.lines().firstOrNull()?.take(50) ?: ""
        if (previewText.isNotEmpty()) {
            builder.withLookupString(snippet.title)
                .withItemTextUnderlined(true)
        }

        return builder
    }

    /**
     * Insert snippet content into the editor.
     *
     * This method:
     * 1. Decrypts the snippet content if E2EE is enabled
     * 2. Replaces the prefix with the snippet content
     * 3. Positions the caret appropriately
     *
     * @param context The insertion context
     * @param snippet The snippet to insert
     * @param project The current project
     * @param startOffset The start offset of the prefix
     * @param prefix The prefix text
     */
    private fun insertSnippet(
        context: InsertionContext,
        snippet: Snippet,
        project: Project,
        startOffset: Int,
        prefix: String
    ) {
        val editor = context.editor
        val document = editor.document

        // Get snippet content (decrypt if needed)
        val content = getSnippetContent(snippet, project)

        if (content == null) {
            LOG.warn("Failed to get content for snippet: ${snippet.id}")
            return
        }

        ApplicationManager.getApplication().runWriteAction {
            try {
                val endOffset = startOffset + prefix.length

                // Replace the prefix with snippet content
                document.replaceString(startOffset, endOffset, content)

                // Move caret to end of inserted content
                val newCaretOffset = startOffset + content.length
                editor.caretModel.moveToOffset(newCaretOffset)

                LOG.debug("Inserted snippet '${snippet.title}' at offset $startOffset")
            } catch (e: Exception) {
                LOG.error("Failed to insert snippet", e)
            }
        }
    }

    /**
     * Get the decrypted content of a snippet.
     *
     * If E2EE is enabled and the snippet is encrypted, this method:
     * 1. Retrieves the user's private key from secure storage
     * 2. Decrypts the data encryption key (DEK) with the private key
     * 3. Decrypts the snippet content with the DEK
     *
     * If the snippet is not encrypted, returns the content as-is.
     *
     * @param snippet The snippet to get content from
     * @param project The current project
     * @return The decrypted content, or null if decryption failed
     */
    private fun getSnippetContent(snippet: Snippet, project: Project): String? {
        return if (snippet.isEncrypted()) {
            decryptSnippetContent(snippet, project)
        } else {
            snippet.content
        }
    }

    /**
     * Decrypt encrypted snippet content using E2EE.
     *
     * This method:
     * 1. Retrieves the E2EE profile from settings
     * 2. Gets the private key from secure storage
     * 3. Parses the encrypted envelope
     * 4. Decrypts the DEK with the private key
     * 5. Decrypts the content with the DEK
     *
     * @param snippet The encrypted snippet
     * @param project The current project
     * @return The decrypted content, or null if decryption failed
     */
    private fun decryptSnippetContent(snippet: Snippet, project: Project): String? {
        return try {
            val settings = SnipHiveSettings.getInstance(project)
            val secureStorage = SecureCredentialStorage.getInstance()
            val email = settings.getUserEmail()

            if (email.isEmpty()) {
                LOG.warn("User email not available for snippet decryption")
                return null
            }

            // Get private key from secure storage (already decrypted)
            val privateKeyJwkStr = secureStorage.getPrivateKey(project, email)

            if (privateKeyJwkStr == null) {
                LOG.warn("Private key not available for snippet decryption")
                return null
            }

            // Parse private key JWK
            val privateKeyJWK = JsonParser.parseString(privateKeyJwkStr).asJsonObject
            val privateKey = RSACrypto.importPrivateKeyFromJWK(privateKeyJWK)

            // Decrypt content using envelope encryption
            val encryptedDek = E2EECryptoService.base64ToArrayBuffer(snippet.encryptedDek ?: "")
            if (encryptedDek.isEmpty()) {
                LOG.warn("No encrypted DEK for snippet: ${snippet.id}")
                return null
            }

            val dekBytes = RSACrypto.decrypt(privateKey, encryptedDek)
            val dek = javax.crypto.spec.SecretKeySpec(dekBytes, "AES")

            // Parse encrypted content as envelope (format: base64(iv).base64(ciphertext))
            val parts = snippet.content.split('.')
            if (parts.size != 2) {
                LOG.warn("Invalid encrypted content format for snippet: ${snippet.id}")
                return null
            }

            val contentIv = E2EECryptoService.base64ToArrayBuffer(parts[0])
            val ciphertext = E2EECryptoService.base64ToArrayBuffer(parts[1])

            // Decrypt content
            val decryptedBytes = AESCrypto.decrypt(dek, ciphertext, contentIv)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            LOG.error("Failed to decrypt snippet content", e)
            null
        }
    }

    /**
     * API response wrapper for snippets list.
     *
     * @property data The array of snippets
     */
    data class SnippetsResponse(
        val data: List<Snippet> = emptyList()
    )
}
