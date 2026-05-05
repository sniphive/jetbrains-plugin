# SnipHive JetBrains Eklentisi - Teknik Dökümantasyon

> **VSCode Versiyonu Referans Dokümanı**
> Bu doküman, SnipHive'un JetBrains IDE eklentisinin tam teknik dökümantasyonudur. VSCode versiyonu geliştirilirken bu doküman referans alınacaktır.

---

## İçindekiler

1. [Proje Genel Bakışı](#1-proje-genel-bakışı)
2. [Teknik Stack](#2-teknik-stack)
3. [Mimari Yapı](#3-mimari-yapı)
4. [Veri Modelleri](#4-veri-modelleri)
5. [Servis Katmanı](#5-servis-katmanı)
6. [E2EE (Uçtan Uca Şifreleme)](#6-e2ee-uçtan-uca-şifreleme)
7. [Kullanıcı Arayüzü Bileşenleri](#7-kullanıcı-arayüzü-bileşenleri)
8. [IDE Entegrasyonları](#8-ide-entegrasyonları)
9. [Aksiyonlar ve Klavye Kısayolları](#9-aksiyonlar-ve-klavye-kısayolları)
10. [API Endpoint'leri](#10-api-endpointleri)
11. [Ayarlar ve Konfigürasyon](#11-ayarlar-ve-konfigürasyon)
12. [Güvenlik](#12-güvenlik)
13. [Test](#13-test)
14. [VSCode Porting İş checklist](#14-vscode-porting-iş-checklist)

---

## 1. Proje Genel Bakışı

### 1.1 Tanım

**SnipHive**, güvenli, uçtan uca şifrelemeli (E2EE) bir kod snippet yöneticisidir. JetBrains IDE'leri (IntelliJ IDEA, PhpStorm, PyCharm, WebStorm, vb.) için geliştirilmiş bir eklentidir.

### 1.2 Temel Özellikler

- **Araç Penceresi Entegrasyonu** - Snippet ve notları tarayabileceğiniz özel araç penceresi
- **Seçimden Oluştur** - Seçili koddan anında snippet oluşturma (`Shift+Alt+S`)
- **Snippet Ekleme** - Aksiyon menüsü veya kod tamamlama ile snippet ekleme
- **Uçtan Uca Şifreleme** - RSA-4096 OAEP + AES-256-GCM ile istemci tarafı şifreleme
- **Güvenli Depolama** - Kimlik bilgileri ve anahtarlar IDE Password Safe'te saklanır
- **Çok Dilli Destek** - Tüm JetBrains IDE'leri ile çalışır
- **Akıllı Arama** - Dil, etiket ve metin bazlı filtreleme
- **Kod Tamamlama** - Yazarken snippet önerileri alma
- **Notlar Desteği** - Markdown formatında notlar
- **Favoriler ve Arşiv Görünümleri**
- **Etiket Yönetimi** - Renkli etiketler oluşturma/düzenleme/silme
- **Çalışma Alanı Değiştirme** - Birden fazla çalışma alanı arasında geçiş
- **GitHub Gist İçe Aktarım** - Gist'leri snippet olarak içe aktarma

### 1.3 Desteklenen IDE'ler

- IntelliJ IDEA (Ultimate & Community)
- PhpStorm
- PyCharm (Professional & Community)
- WebStorm
- RubyMine
- GoLand
- CLion
- Rider
- DataGrip
- AppCode
- Android Studio

**Minimum Versiyon:** 2023.2 (build 232)

---

## 2. Teknik Stack

### 2.1 Backend

| Bileşen | Teknoloji |
|---------|-----------|
| Dil | Kotlin 2.0.21 |
| Framework | IntelliJ Platform Plugin SDK 2.13.1 |
| Runtime | JVM 17 (Java toolchain) |
| Build Araçı | Gradle 8.x (Kotlin DSL) |
| HTTP Client | OkHttp 4.12.0 |
| JSON | Gson 2.10.1 |
| Kriptografi | Bouncy Castle 1.76 |

### 2.2 Eklenti Konfigürasyonu

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "232"  // IntelliJ 2023.2+
            untilBuild = "241.*" // IntelliJ 2024.1
        }
    }
}
```

### 2.3 Bağımlılıklar

```kotlin
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.76")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.7.0")
}
```

---

## 3. Mimari Yapı

### 3.1 Proje Yapısı

```
jetbrains-sniphive/
├── src/
│   ├── main/
│   │   ├── kotlin/com/sniphive/idea/
│   │   │   ├── actions/              # IDE aksiyonları
│   │   │   │   ├── CreateSnippetAction.kt
│   │   │   │   ├── InsertSnippetAction.kt
│   │   │   │   ├── ShowRecentSnippetsAction.kt
│   │   │   │   ├── RefreshSnippetsAction.kt
│   │   │   │   ├── ManageTagsAction.kt
│   │   │   │   ├── GistImportAction.kt
│   │   │   │   ├── OpenSnipHiveSettingsAction.kt
│   │   │   │   └── OpenE2EESetupAction.kt
│   │   │   ├── completion/           # Kod tamamlama
│   │   │   │   ├── SnippetCompletionProvider.kt
│   │   │   │   └── SnippetLookupService.kt
│   │   │   ├── config/              # Ayarlar
│   │   │   │   ├── SnipHiveSettings.kt
│   │   │   │   └── SnipHiveSettingsConfigurable.kt
│   │   │   ├── crypto/              # E2EE kriptografi
│   │   │   │   ├── E2EECryptoService.kt
│   │   │   │   ├── EnvelopeEncryption.kt
│   │   │   │   ├── RSACrypto.kt
│   │   │   │   ├── AESCrypto.kt
│   │   │   │   └── PBKDF2.kt
│   │   │   ├── editor/              # Özel editörler
│   │   │   │   ├── SnippetEditor.kt
│   │   │   │   ├── SnippetEditorProvider.kt
│   │   │   │   ├── SnippetVirtualFile.kt
│   │   │   │   ├── SnippetFileType.kt
│   │   │   │   ├── NoteEditor.kt
│   │   │   │   ├── NoteEditorProvider.kt
│   │   │   │   ├── NoteVirtualFile.kt
│   │   │   │   ├── NoteFileType.kt
│   │   │   │   ├── EditorUtils.kt
│   │   │   │   └── E2EEContentService.kt
│   │   │   ├── models/              # Veri modelleri
│   │   │   │   ├── Snippet.kt
│   │   │   │   ├── Note.kt
│   │   │   │   ├── Tag.kt
│   │   │   │   ├── Workspace.kt
│   │   │   │   └── LoginResponse.kt
│   │   │   ├── services/            # İş mantığı servisleri
│   │   │   │   ├── SnipHiveApiClient.kt
│   │   │   │   ├── SnipHiveApiService.kt
│   │   │   │   ├── SnipHiveAuthService.kt
│   │   │   │   ├── SecureCredentialStorage.kt
│   │   │   │   ├── SnippetLookupService.kt
│   │   │   │   └── NoteLookupService.kt
│   │   │   ├── status/             # Status bar widget
│   │   │   │   └── SnipHiveStatusBarWidget.kt
│   │   │   ├── toolwindow/         # Araç penceresi
│   │   │   │   └── SnipHiveToolWindowFactory.kt
│   │   │   └── ui/                 # UI bileşenleri
│   │   │       ├── LoginDialog.kt
│   │   │       ├── CreateSnippetDialog.kt
│   │   │       ├── InsertSnippetDialog.kt
│   │   │       ├── CreateNoteDialog.kt
│   │   │       ├── E2EESetupDialog.kt
│   │   │       ├── MasterPasswordDialog.kt
│   │   │       ├── SnippetListPanel.kt
│   │   │       ├── NoteListPanel.kt
│   │   │       ├── SnippetDetailPanel.kt
│   │   │       ├── NoteDetailPanel.kt
│   │   │       ├── FavoritesPanel.kt
│   │   │       ├── ArchivePanel.kt
│   │   │       ├── PinnedPanel.kt
│   │   │       ├── SearchPanel.kt
│   │   │       ├── WorkspaceSelector.kt
│   │   │       ├── TagDialogs.kt
│   │   │       ├── GistImportDialog.kt
│   │   │       ├── ItemActionHandler.kt
│   │   │       └── RecentSnippetsPopup.kt
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── plugin.xml      # Eklenti tanımlayıcı
│   │       └── icons/
│   │           └── sniphive_toolwindow.svg
│   └── test/
│       └── kotlin/com/sniphive/idea/
│           ├── crypto/
│           │   ├── E2EECryptoServiceTest.kt
│           │   ├── RSACryptoTest.kt
│           │   ├── AESCryptoTest.kt
│           │   ├── PBKDF2Test.kt
│           │   └── EnvelopeEncryptionTest.kt
│           ├── services/
│           │   └── SnipHiveApiClientTest.kt
│           └── ui/
│               └── ItemActionHandlerTest.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── DESCRIPTION.html
└── README.md
```

### 3.2 Mimari Desenler

#### Service Layer Pattern
Uygulama ve proje servisleri `plugin.xml`'de kayıtlıdır:

```xml
<extensions>
    <applicationService serviceImplementation="com.sniphive.idea.services.SnipHiveAuthService"/>
    <applicationService serviceImplementation="com.sniphive.idea.services.SnipHiveApiClient"/>
    <applicationService serviceImplementation="com.sniphive.idea.services.SnipHiveApiService"/>
    <applicationService serviceImplementation="com.sniphive.idea.services.SecureCredentialStorage"/>
    <projectService serviceImplementation="com.sniphive.idea.services.SnippetLookupService"/>
    <projectService serviceImplementation="com.sniphive.idea.services.NoteLookupService"/>
</extensions>
```

**Servisler:**
- `SnipHiveAuthService` - Kimlik doğrulama durumu yönetimi
- `SnipHiveApiClient` - Backend HTTP iletişimi
- `SnipHiveApiService` - API işlemleri (CRUD)
- `SecureCredentialStorage` - Password Safe entegrasyonu
- `SnippetLookupService` - Snippet arama ve önbellekleme
- `NoteLookupService` - Not arama ve önbellekleme

#### Action Pattern
IDE aksiyonları `plugin.xml`'de kayıtlı ve klavye kısayolları atanmıştır:

```xml
<actions>
    <action id="com.sniphive.idea.actions.CreateSnippetAction"
            class="com.sniphive.idea.actions.CreateSnippetAction"
            text="Create Snippet">
        <keyboard-shortcut keymap="$default" first-keystroke="shift alt S"/>
    </action>
</actions>
```

#### Tool Window Pattern
Sağa yaslanmış araç penceresi, birden fazla panel içerir:

```xml
<toolWindow id="SnipHive"
            factoryClass="com.sniphive.idea.toolwindow.SnipHiveToolWindowFactory"
            anchor="right"
            icon="/icons/sniphive_toolwindow.svg"/>
```

#### Code Completion Pattern
Dil-agnostik tamamlama sağlayıcısı:

```xml
<completion.contributor
    language="any"
    implementationClass="com.sniphive.idea.completion.SnippetCompletionProvider"
    order="before default"/>
```

#### Custom Editor Pattern
Özel sanal dosyalar için FileEditorProvider:

```xml
<fileEditorProvider implementation="com.sniphive.idea.editor.SnippetEditorProvider"/>
<fileEditorProvider implementation="com.sniphive.idea.editor.NoteEditorProvider"/>
```

---

## 4. Veri Modelleri

### 4.1 Snippet

```kotlin
data class Snippet(
    val id: String,                          // Benzersiz snippet ID
    val uuid: String? = null,                // UUID
    val slug: String? = null,                 // URL-dostu slug
    val title: String,                        // Başlık
    val content: String,                      // İçerik (E2EE açıkken şifreli)
    val language: String? = null,            // Programlama dili
    val encryptedDek: String? = null,         // Şifrelenmiş DEK (E2EE için)
    val isPublic: Boolean = false,           // Herkese açık mı
    val isPinned: Boolean = false,          // Sabitlenmiş mi
    val isFavorite: Boolean = false,          // Favori mi
    val archivedAt: String? = null,          // Arşivleme tarihi
    val url: String? = null,                 // İç URL
    val publicUrl: String? = null,           // Herkese açık URL
    val createdAt: String? = null,           // Oluşturulma tarihi
    val updatedAt: String? = null,           // Son güncelleme tarihi
    val user: User? = null,                   // Oluşturan kullanıcı
    val tags: List<Tag> = emptyList()        // Etiketler
) {
    fun isArchived(): Boolean = archivedAt != null
    fun isEncrypted(): Boolean = encryptedDek != null
    fun getDisplayLanguage(): String = language ?: "Plain Text"
}
```

### 4.2 Note

```kotlin
data class Note(
    val id: String,
    val slug: String? = null,
    val uuid: String? = null,
    val title: String,
    val content: String,                      // Markdown formatında içerik
    val encryptedDek: String? = null,
    val isPublic: Boolean = false,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val archivedAt: String? = null,
    val url: String? = null,
    val publicUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val user: User? = null,
    val tags: List<Tag> = emptyList()
) {
    fun isArchived(): Boolean = archivedAt != null
    fun isEncrypted(): Boolean = encryptedDek != null
    fun getPreview(maxLines: Int = 3): String
}
```

### 4.3 Workspace

```kotlin
data class Workspace(
    val id: String,                          // Benzersiz çalışma alanı ID
    val uuid: String? = null,
    val name: String,                         // Çalışma alanı adı
    val type: String? = null,                 // "personal" veya "team"
    val role: String? = null,                 // "owner", "admin", "member", "viewer"
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    fun isPersonal(): Boolean = type == "personal"
    fun isTeam(): Boolean = type == "team"
    fun isOwner(): Boolean = role == "owner"
    fun isAdmin(): Boolean = role == "admin" || role == "owner"
}
```

### 4.4 Tag

```kotlin
data class Tag(
    val id: String,
    val name: String,
    val slug: String? = null,
    val color: String? = null,                // Hex renk kodu
    val snippetsCount: Int? = null,
    val notesCount: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    fun getTotalCount(): Int = (snippetsCount ?: 0) + (notesCount ?: 0)
    fun getColorOrDefault(): String = color ?: "#6366f1"
}
```

### 4.5 LoginResponse

```kotlin
data class LoginResponse(
    @SerializedName("token")
    val token: String,                        // Sanctum Bearer token
    @SerializedName("user")
    val user: User? = null,
    @SerializedName("workspaces")
    val workspaces: List<Workspace> = emptyList()
) {
    data class User(
        val id: String,
        val name: String?,
        val email: String?
    )
}
```

---

## 5. Servis Katmanı

### 5.1 SnipHiveApiClient

HTTP istemcisi. Tüm API isteklerini yönetir.

**Özellikler:**
- JSON serialization/deserialization (Gson)
- Bearer token kimlik doğrulama
- İstek/yanıt loglama
- Hata yönetimi ve retry mantığı
- Rate limiting farkındalığı
- Laravel JsonResource unwrapping

**Ana Metodlar:**

```kotlin
class SnipHiveApiClient {
    // GET isteği
    fun <T> get(
        apiUrl: String,
        endpoint: String,
        token: String? = null,
        queryParams: Map<String, String>? = null,
        responseType: Class<T>,
        workspaceId: String? = null
    ): ApiResponse<T>

    // Sayfalanmış GET isteği
    fun <T> getPaginated(
        apiUrl: String,
        endpoint: String,
        token: String? = null,
        queryParams: Map<String, String>? = null,
        itemClass: Class<T>,
        workspaceId: String? = null
    ): List<T>

    // POST isteği
    fun <T> post(
        apiUrl: String,
        endpoint: String,
        token: String? = null,
        body: Any? = null,
        responseType: Class<T>,
        workspaceId: String? = null
    ): ApiResponse<T>

    // PUT isteği
    fun <T> put(...): ApiResponse<T>

    // PATCH isteği
    fun <T> patch(...): ApiResponse<T>

    // DELETE isteği
    fun delete(apiUrl: String, endpoint: String, token: String, workspaceId: String? = null): ApiResponse<Unit>
}

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap()
) {
    fun isAuthError(): Boolean
    fun isValidationError(): Boolean
    fun isRateLimitError(): Boolean
    fun isServerError(): Boolean
}
```

### 5.2 SnipHiveApiService

Üst seviye API servisleri. CRUD işlemleri gerçekleştirir.

**Ana Metodlar:**

```kotlin
class SnipHiveApiService {
    // --- Snippet CRUD ---
    fun getSnippets(token: String, apiUrl: String, workspaceId: String?): List<Snippet>
    fun getSnippet(token: String, apiUrl: String, snippetId: String, workspaceId: String?): Snippet?
    fun createSnippet(token: String, apiUrl: String, snippet: CreateSnippetRequest, workspaceId: String?): Snippet?
    fun updateSnippet(token: String, apiUrl: String, snippetId: String, snippet: UpdateSnippetRequest, workspaceId: String?): Snippet?
    fun deleteSnippet(token: String, apiUrl: String, snippetId: String, workspaceId: String?): Boolean
    fun archiveSnippet(token: String, apiUrl: String, snippetId: String, workspaceId: String?): Snippet?
    fun restoreSnippet(token: String, apiUrl: String, snippetId: String, workspaceId: String?): Snippet?
    fun toggleFavorite(token: String, apiUrl: String, snippetId: String, workspaceId: String?): Snippet?
    fun togglePinned(token: String, apiUrl: String, snippetId: String, workspaceId: String?): Snippet?

    // --- Note CRUD ---
    fun getNotes(token: String, apiUrl: String, workspaceId: String?): List<Note>
    fun createNote(token: String, apiUrl: String, note: CreateNoteRequest, workspaceId: String?): Note?
    fun updateNote(token: String, apiUrl: String, noteId: String, note: UpdateNoteRequest, workspaceId: String?): Note?
    fun deleteNote(token: String, apiUrl: String, noteId: String, workspaceId: String?): Boolean
    // ... benzer CRUD metodları

    // --- Workspace ---
    fun getWorkspaces(token: String, apiUrl: String): List<Workspace>

    // --- Tags ---
    fun getTags(token: String, apiUrl: String, workspaceId: String?): List<Tag>
    fun createTag(token: String, apiUrl: String, tag: CreateTagRequest, workspaceId: String?): Tag?
    fun updateTag(token: String, apiUrl: String, tagId: String, tag: UpdateTagRequest, workspaceId: String?): Tag?
    fun deleteTag(token: String, apiUrl: String, tagId: String, workspaceId: String?): Boolean

    // --- Gist Import ---
    fun importGist(token: String, apiUrl: String, gistUrl: String, workspaceId: String?): List<Snippet>

    // --- E2EE Profile ---
    fun setupE2EEProfile(token: String, apiUrl: String, profile: E2EEProfileRequest): E2EEProfileResponse?
    fun getE2EEProfile(token: String, apiUrl: String): E2EEProfileResponse?
}
```

### 5.3 SnipHiveAuthService

Kimlik doğrulama servisi. Login ve token yönetimi.

**Ana Metodlar:**

```kotlin
class SnipHiveAuthService {
    fun login(project: Project?, apiUrl: String, email: String, password: String): LoginResult
    fun getAuthToken(project: Project?, email: String): String?
    fun isAuthenticated(project: Project?, email: String): Boolean
    fun isCurrentAuthenticated(project: Project): Boolean
    fun logout(project: Project?, apiUrl: String, email: String, notifyApi: Boolean = true): Boolean
    fun verifyToken(project: Project?, apiUrl: String, email: String): Boolean
    fun clearAllCredentials(project: Project?, email: String): Boolean
}

data class LoginResult(
    val success: Boolean,
    val message: String,
    val user: LoginResponse.User? = null,
    val workspaces: List<LoginResponse.Workspace> = emptyList()
)
```

### 5.4 SecureCredentialStorage

IDE Password Safe entegrasyonu. Hassas verileri güvenli şekilde saklar.

**Ana Metodlar:**

```kotlin
class SecureCredentialStorage {
    fun storeAuthToken(project: Project?, email: String, token: String): Boolean
    fun getAuthToken(project: Project?, email: String): String?
    fun removeAuthToken(project: Project?, email: String): Boolean
    fun storePassword(project: Project?, email: String, password: String): Boolean
    fun getPassword(project: Project?, email: String): String?
    fun storePrivateKey(project: Project?, email: String, privateKey: String): Boolean
    fun getPrivateKey(project: Project?, email: String): String?
    fun storeMasterPasswordHash(project: Project?, email: String, hash: String): Boolean
    fun getMasterPasswordHash(project: Project?, email: String): String?
    fun removeAllCredentialsForUser(project: Project?, email: String): Boolean
}
```

### 5.5 SnippetLookupService & NoteLookupService

Arama ve önbellekleme servisleri (Project-level).

**Ana Metodlar:**

```kotlin
class SnippetLookupService {
    fun getCachedSnippets(): List<Snippet>
    fun searchSnippets(query: String, language: String? = null, tags: List<String> = emptyList()): List<Snippet>
    fun getSnippetById(id: String): Snippet?
    fun refreshSnippets(): Boolean
    fun invalidateCache()
}

class NoteLookupService {
    // Benzer metodlar
}
```

---

## 6. E2EE (Uçtan Uca Şifreleme)

### 6.1 Mimari

SnipHive, "zero-knowledge" E2EE mimarisi kullanır:

```
┌─────────────────┐                          ┌─────────────────┐
│   Client App     │                          │   Server         │
│                 │                          │                 │
│  ┌───────────┐  │                          │  ┌───────────┐  │
│  │  Plaintext │  │                          │  │ Encrypted │  │
│  │  Content   │  │                          │  │ Content   │  │
│  └─────┬─────┘  │                          │  └─────┬─────┘  │
│        │        │                          │        │        │
│  ┌─────▼─────┐  │                          │        │        │
│  │ AES-256   │◄─┼──── DEK (encrypted) ────►│        │        │
│  │ GCM       │  │                          │        │        │
│  └─────┬─────┘  │                          │        │        │
│        │        │                          │        │        │
│  ┌─────▼─────┐  │      ┌──────────────┐    │        │        │
│  │  RSA-4096 │◄─┼──────┤ Master Key   ├────┼────────┘        │
│  │  OAEP     │  │      │ (derived from│    │                 │
│  └───────────┘  │      │  password)   │    │                 │
│                 │      └──────────────┘    │                 │
└─────────────────┘                          └─────────────────┘
```

### 6.2 Anahtar Üretimi ve Türetimi

**E2EE Setup Süreci:**

1. **RSA Anahtar Çifti Üretimi**
   ```kotlin
   val keyPair = RSACrypto.generateKeyPair()  // RSA-4096
   val privateKeyJWK = RSACrypto.exportPrivateKeyToJWK(keyPair.private)
   val publicKeyJWK = RSACrypto.exportPublicKeyToJWK(keyPair.public)
   ```

2. **Anahtar Türetme (PBKDF2)**
   ```kotlin
   val salt = PBKDF2.generateSalt()  // 16 bytes
   val masterKey = PBKDF2.deriveKey(masterPassword, salt)
   // iterations: 600,000 (OWASP önerisi)
   // algorithm: HmacSHA256
   ```

3. **Özel Anahtar Şifreleme (AES-256-GCM)**
   ```kotlin
   val masterIV = AESCrypto.generateIV()  // 12 bytes
   val encryptedPrivateKey = AESCrypto.encrypt(masterKey, privateKeyBytes, masterIV)
   ```

4. **Kurtarma Kodu Üretimi**
   ```kotlin
   val recoveryCode = E2EECryptoService.generateRecoveryCode()
   // Format: "ABCD-EFGH-IJKL-MNOP-QRST" (24 karakter)
   // Karakterler: ABCDEFGHJKLMNPQRSTUVWXYZ23456789 (I, O, 0, 1 hariç)
   ```

### 6.3 Şifreleme/Şifre Çözme Akışı

**Snippet Oluşturma (Şifreleme):**

```
1. Her snippet için rastgele DEK (Data Encryption Key) üretilir (AES-256)
2. İçerik DEK ile AES-256-GCM ile şifrelenir
3. DEK, kullanıcının açık RSA anahtarı ile şifrelenir (RSA-OAEP)
4. Sunucuya şunlar gönderilir:
   - encrypted_content: Şifrelenmiş içerik
   - encrypted_dek: DEK'in RSA şifreli hali
   - iv: AES IV
   - tags: (plaintext)
   - diğer metadata
```

**Snippet Okuma (Şifre Çözme):**

```
1. encrypted_dek, kullanıcının özel RSA anahtarı ile çözülür → DEK
2. DEK ile encrypted_content AES-256-GCM ile çözülür → plaintext
```

### 6.4 Kriptografi Sınıfları

#### RSACrypto.kt

```kotlin
object RSACrypto {
    const val KEY_SIZE = 4096
    const val ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    fun generateKeyPair(): KeyPair
    fun exportPrivateKeyToJWK(privateKey: PrivateKey): JsonObject
    fun exportPublicKeyToJWK(publicKey: PublicKey): JsonObject
    fun importPrivateKeyFromJWK(jwk: JsonObject): PrivateKey
    fun importPublicKeyFromJWK(jwk: JsonObject): PublicKey
    fun encrypt(publicKey: PublicKey, data: ByteArray): ByteArray
    fun decrypt(privateKey: PrivateKey, encrypted: ByteArray): ByteArray
}
```

#### AESCrypto.kt

```kotlin
object AESCrypto {
    const val KEY_SIZE = 256
    const val ALGORITHM = "AES/GCM/NoPadding"
    const val IV_SIZE = 12
    const val TAG_SIZE = 128

    fun generateKey(): SecretKey
    fun generateIV(): ByteArray
    fun encrypt(key: SecretKey, plaintext: ByteArray, iv: ByteArray): ByteArray
    fun decrypt(key: SecretKey, ciphertext: ByteArray, iv: ByteArray): ByteArray
    fun encryptToString(key: SecretKey, plaintext: ByteArray, iv: ByteArray): String
    fun decryptToString(key: SecretKey, ciphertext: ByteArray, iv: ByteArray): String
}
```

#### PBKDF2.kt

```kotlin
object PBKDF2 {
    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PBKDF2_ITERATIONS = 600000  // OWASP önerisi
    const val SALT_SIZE = 16
    const val KEY_SIZE = 256

    fun generateSalt(): ByteArray
    fun deriveKey(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey
}
```

#### EnvelopeEncryption.kt

```kotlin
class EnvelopeEncryption {
    data class EncryptedEnvelope(
        val encryptedContent: ByteArray,
        val encryptedDek: ByteArray,
        val iv: ByteArray,
        val algorithm: String
    )

    fun encryptContent(content: String, publicKey: PublicKey): EncryptedEnvelope
    fun decryptContent(envelope: EncryptedEnvelope, privateKey: PrivateKey): String
}
```

### 6.5 Unlock İşlemleri

**Master Password ile:**

```kotlin
fun unlockWithMasterPassword(masterPassword: String, profile: E2EEProfile): PrivateKey {
    val salt = base64ToArrayBuffer(profile.kdfSalt)
    val iv = base64ToArrayBuffer(profile.privateKeyIV)
    val encryptedPrivateKey = base64ToArrayBuffer(profile.encryptedPrivateKey)
    val masterKey = PBKDF2.deriveKey(masterPassword, salt, profile.kdfIterations)
    val decryptedPrivateKeyJWK = AESCrypto.decryptToString(masterKey, encryptedPrivateKey, iv)
    return RSACrypto.importPrivateKeyFromJWK(JsonParser.parseString(decryptedPrivateKeyJWK).asJsonObject)
}
```

**Recovery Code ile:**

```kotlin
fun unlockWithRecoveryCode(recoveryCode: String, profile: E2EEProfile): PrivateKey {
    val parsedCode = parseRecoveryCode(recoveryCode)
    val recoverySalt = base64ToArrayBuffer(profile.recoverySalt ?: profile.kdfSalt)
    val recoveryKey = PBKDF2.deriveKey(parsedCode, recoverySalt, profile.kdfIterations)
    val decryptedPrivateKeyJWK = AESCrypto.decryptToString(recoveryKey, encryptedPrivateKey, iv)
    return RSACrypto.importPrivateKeyFromJWK(privateKeyJWK)
}
```

---

## 7. Kullanıcı Arayüzü Bileşenleri

### 7.1 Diyaloglar

| Diyalog | Sınıf | Açıklama |
|---------|-------|----------|
| Login | `LoginDialog` | Email/password ile giriş |
| Create Snippet | `CreateSnippetDialog` | Snippet oluşturma formu |
| Insert Snippet | `InsertSnippetDialog` | Snippet arama ve seçme |
| Create Note | `CreateNoteDialog` | Not oluşturma formu |
| E2EE Setup | `E2EESetupDialog` | E2EE kurulum sihirbazı |
| Master Password | `MasterPasswordDialog` | E2EE unlock |
| Tag Management | `TagDialogs` | Etiket oluşturma/düzenleme |
| Gist Import | `GistImportDialog` | GitHub Gist içe aktarım |

### 7.2 Paneller

| Panel | Sınıf | Açıklama |
|-------|-------|----------|
| Snippet List | `SnippetListPanel` | Snippet listesi ve filtreleme |
| Note List | `NoteListPanel` | Not listesi |
| Snippet Detail | `SnippetDetailPanel` | Snippet önizleme/düzenleme |
| Note Detail | `NoteDetailPanel` | Not önizleme/düzenleme |
| Favorites | `FavoritesPanel` | Favori öğeler |
| Archive | `ArchivePanel` | Arşivlenmiş öğeler |
| Pinned | `PinnedPanel` | Sabitlenmiş öğeler |
| Search | `SearchPanel` | Gelişmiş arama |

### 7.3 Araç Penceresi

```xml
<toolWindow id="SnipHive"
            factoryClass="com.sniphive.idea.toolwindow.SnipHiveToolWindowFactory"
            anchor="right"
            icon="/icons/sniphive_toolwindow.svg"/>
```

**SnipHiveToolWindowFactory:**
- Sidebar tabs: Snippets, Notes, Favorites, Pinned, Archive
- Her tab ilgili paneli gösterir
- Settings ve search bar üstte

---

## 8. IDE Entegrasyonları

### 8.1 Actions (plugin.xml)

```xml
<actions>
    <action id="com.sniphive.idea.actions.CreateSnippetAction"
            text="Create Snippet"
            description="Create a new SnipHive snippet from selected code">
        <add-to-group group-id="EditorPopupMenu" anchor="first"/>
        <add-to-group group-id="ProjectViewPopupMenu" anchor="after" relative-to-action="EditSource"/>
        <keyboard-shortcut keymap="$default" first-keystroke="shift alt S"/>
    </action>

    <action id="com.sniphive.idea.actions.InsertSnippetAction"
            text="Insert Snippet..."
            description="Insert a SnipHive snippet at cursor position">
        <add-to-group group-id="EditorPopupMenu" anchor="after" relative-to-action="CreateSnippetAction"/>
        <add-to-group group-id="GenerateGroup" anchor="last"/>
        <keyboard-shortcut keymap="$default" first-keystroke="shift alt I"/>
    </action>

    <group id="SnipHive.Menu" text="SnipHive" popup="true">
        <reference ref="com.sniphive.idea.actions.CreateSnippetAction"/>
        <reference ref="com.sniphive.idea.actions.InsertSnippetAction"/>
        <separator/>
        <action id="com.sniphive.idea.actions.RefreshSnippetsAction" text="Refresh Snippets"/>
        <action id="com.sniphive.idea.actions.OpenE2EESetupAction" text="Setup E2EE..."/>
        <action id="com.sniphive.idea.actions.ManageTagsAction" text="Manage Tags..."/>
        <action id="com.sniphive.idea.actions.GistImportAction" text="Import GitHub Gist..."/>
        <action id="com.sniphive.idea.actions.ShowRecentSnippetsAction" text="Recent Snippets">
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl shift E"/>
        </action>
        <separator/>
        <action id="com.sniphive.idea.actions.OpenSnipHiveSettingsAction" text="Settings..."/>
        <add-to-group group-id="MainMenu" anchor="after" relative-to-action="ToolsMenu"/>
    </group>
</actions>
```

### 8.2 Code Completion

```xml
<completion.contributor
    language="any"
    implementationClass="com.sniphive.idea.completion.SnippetCompletionProvider"
    order="before default"/>
```

**SnippetCompletionProvider:**
- Herhangi bir dilde tetiklenir (language="any")
- 2+ karakter yazıldığında aktifleşir (yapılandırılabilir)
- Snippet başlıklarına göre önerir
- Seçildiğinde içeriği editöre eklenir

### 8.3 Custom Editors

**SnippetEditorProvider / NoteEditorProvider:**
- Özel virtual file tipi oluşturur
- SnippetEditor / NoteEditor ile düzenleme imkanı
- E2EE şifre çözme otomatik tetiklenir

### 8.4 Status Bar Widget

```xml
<statusBarWidgetFactory id="SnipHiveStatusBar"
                        implementation="com.sniphive.idea.status.SnipHiveStatusBarWidgetFactory"
                        order="after Position"/>
```

---

## 9. Aksiyonlar ve Klavye Kısayolları

### 9.1 Tüm Aksiyonlar

| Aksiyon | ID | Kısayol | Menü |
|---------|-----|---------|------|
| CreateSnippetAction | `com.sniphive.idea.actions.CreateSnippetAction` | `Shift+Alt+S` | EditorPopupMenu |
| InsertSnippetAction | `com.sniphive.idea.actions.InsertSnippetAction` | `Shift+Alt+I` | EditorPopupMenu, GenerateGroup |
| RefreshSnippetsAction | `com.sniphive.idea.actions.RefreshSnippetsAction` | - | SnipHive.Menu |
| OpenE2EESetupAction | `com.sniphive.idea.actions.OpenE2EESetupAction` | - | SnipHive.Menu |
| ManageTagsAction | `com.sniphive.idea.actions.ManageTagsAction` | - | SnipHive.Menu |
| GistImportAction | `com.sniphive.idea.actions.GistImportAction` | - | SnipHive.Menu |
| ShowRecentSnippetsAction | `com.sniphive.idea.actions.ShowRecentSnippetsAction` | `Ctrl+Shift+E` | SnipHive.Menu |
| OpenSnipHiveSettingsAction | `com.sniphive.idea.actions.OpenSnipHiveSettingsAction` | - | SnipHive.Menu |

### 9.2 Varsayılan Kısayollar

| Aksiyon | Kısayol |
|--------|---------|
| Create Snippet | `Shift+Alt+S` |
| Insert Snippet | `Shift+Alt+I` |
| Recent Snippets | `Ctrl+Shift+E` |

---

## 10. API Endpointleri

### 10.1 Kimlik Doğrulama

| Endpoint | Metod | Açıklama |
|----------|-------|----------|
| `/api/v1/login` | POST | Email/password ile giriş |

### 10.2 Snippets

| Endpoint | Metod | Açıklama |
|----------|-------|----------|
| `/api/v1/snippets` | GET | Snippet listesi (sayfalanmış) |
| `/api/v1/snippets/{id}` | GET | Snippet detayı |
| `/api/v1/snippets` | POST | Snippet oluşturma |
| `/api/v1/snippets/{id}` | PUT | Snippet güncelleme |
| `/api/v1/snippets/{id}` | PATCH | Snippet kısmi güncelleme |
| `/api/v1/snippets/{id}` | DELETE | Snippet silme |
| `/api/v1/snippets/{id}/archive` | POST | Snippet arşivleme |
| `/api/v1/snippets/{id}/restore` | POST | Snippet geri yükleme |
| `/api/v1/snippets/{id}/favorite` | POST | Favori toggle |
| `/api/v1/snippets/{id}/pin` | POST | Pin toggle |

### 10.3 Notes

| Endpoint | Metod | Açıklama |
|----------|-------|----------|
| `/api/v1/notes` | GET | Not listesi |
| `/api/v1/notes/{id}` | GET | Not detayı |
| `/api/v1/notes` | POST | Not oluşturma |
| `/api/v1/notes/{id}` | PUT | Not güncelleme |
| `/api/v1/notes/{id}` | DELETE | Not silme |
| `/api/v1/notes/{id}/archive` | POST | Not arşivleme |
| `/api/v1/notes/{id}/restore` | POST | Not geri yükleme |
| `/api/v1/notes/{id}/favorite` | POST | Favori toggle |
| `/api/v1/notes/{id}/pin` | POST | Pin toggle |

### 10.4 Workspaces

| Endpoint | Metod | Açıklama |
|----------|-------|----------|
| `/api/v1/workspaces` | GET | Çalışma alanı listesi |

### 10.5 Tags

| Endpoint | Metod | Açıklama |
|----------|-------|----------|
| `/api/v1/tags` | GET | Etiket listesi |
| `/api/v1/tags` | POST | Etiket oluşturma |
| `/api/v1/tags/{id}` | PUT | Etiket güncelleme |
| `/api/v1/tags/{id}` | DELETE | Etiket silme |

### 10.6 E2EE

| Endpoint | Metod | Açıklama |
|----------|-------|----------|
| `/api/v1/e2ee/profile` | GET | E2EE profilini al |
| `/api/v1/e2ee/profile` | POST | E2EE profilini oluştur |

### 10.7 Import

| Endpoint | Metod | Açıklama |
|----------|-------|----------|
| `/api/v1/import/gist` | POST | GitHub Gist içe aktarım |

---

## 11. Ayarlar ve Konfigürasyon

### 11.1 SnipHiveSettings

```kotlin
data class State(
    var apiUrl: String = "https://api.sniphive.net",
    var workspaceId: String = "",
    var userEmail: String = "",
    var e2eeEnabled: Boolean = true,
    var userName: String = "",
    var showEncryptedContent: Boolean = true,
    var autoRefreshOnOpen: Boolean = true,
    var autoRefreshIntervalMinutes: Int = 0,
    var enableCodeCompletion: Boolean = true,
    var codeCompletionMinPrefixLength: Int = 3,
    var codeCompletionMaxSuggestions: Int = 10,
    var masterPasswordHint: String = "",
    var e2eeIterations: Int = 100000,
    var e2eeUnlocked: Boolean = false,
    var lastUnlockTime: Long = 0L,
    var rememberMasterPassword: Boolean = true
)
```

### 11.2 Settings Konfigürasyonu

```xml
<applicationConfigurable
    parentId="tools"
    instance="com.sniphive.idea.config.SnipHiveSettingsConfigurable"
    id="com.sniphive.idea.config.SnipHiveSettingsConfigurable"
    displayName="SnipHive"/>
```

---

## 12. Güvenlik

### 12.1 Şifreleme Standartları

| Bileşen | Algoritma | Detay |
|---------|-----------|-------|
| Anahtar Değişimi | RSA-4096 OAEP | 4096-bit anahtar, SHA-256 |
| İçerik Şifreleme | AES-256-GCM | 256-bit anahtar, 128-bit auth tag |
| Anahtar Türetme | PBKDF2 | 600,000 iterasyon, HmacSHA256 |
| Rastgele Veri | SecureRandom | Cryptographically secure |

### 12.2 Veri Güvenliği

| Veri Türü | Depolama | Açıklama |
|-----------|----------|----------|
| Auth Token | IDE Password Safe | Kullanıcıya özel |
| Master Password Hash | IDE Password Safe | Kullanıcıya özel |
| Private Key (E2EE) | IDE Password Safe | Şifrelenmiş |
| API URL | settings.xml | Plaintext |
| Workspace ID | settings.xml | Plaintext |
| User Email | settings.xml | Display only |

### 12.3 Önemli Güvenlik Notları

- Şifreleme anahtarları cihaz dışına **hiçbir zaman** çıkmaz
- Sunucu plaintext içeriği **hiçbir zaman** göremez
- Kimlik bilgileri IDE Password Safe'te saklanır
- Recovery code **tek** kurtarma yöntemidir

---

## 13. Test

### 13.1 Test Komutları

```bash
# Tüm unit testleri çalıştır
./gradlew test

# Belirli test sınıfı
./gradlew test --tests "com.sniphive.idea.crypto.E2EECryptoServiceTest"

# Detaylı çıktı
./gradlew test --info
```

### 13.2 Test Kapsamı

**Unit Testler:**
- `E2EECryptoServiceTest` - E2EE setup ve unlock
- `RSACryptoTest` - RSA anahtar üretimi, import/export
- `AESCryptoTest` - AES şifreleme/şifre çözme
- `PBKDF2Test` - Anahtar türetme
- `EnvelopeEncryptionTest` - Zarf şifreleme
- `SnipHiveApiClientTest` - HTTP client
- `ItemActionHandlerTest` - UI event handling

---

## 14. VSCode Porting İş Checklist

### 14.1 Mimari Adaptasyonlar

| JetBrains Kavramı | VSCode Karşılığı |
|-------------------|------------------|
| IntelliJ Platform SDK | VSCode Extension API |
| Plugin.xml | package.json |
| ApplicationService | Memento pattern / services |
| ProjectService | Workspace-scoped storage |
| ToolWindow | WebviewPanel / Sidebar |
| FileEditorProvider | Custom TextDocumentEditor |
| Action System | VSCode Commands |
| StatusBarWidget | StatusBarItem |
| CompletionContributor | CompletionItemProvider |

### 14.2 Porting Tablosu

| Bileşen | JetBrains (Kotlin) | VSCode (TypeScript) |
|---------|---------------------|---------------------|
| **Core** | | |
| API Client | `SnipHiveApiClient.kt` | `src/services/apiClient.ts` |
| Auth Service | `SnipHiveAuthService.kt` | `src/services/authService.ts` |
| Settings | `SnipHiveSettings.kt` | `src/services/settings.ts` |
| Secure Storage | `SecureCredentialStorage.kt` | VSCode `secretStorage` API |
| **Crypto** | | |
| E2EE Service | `E2EECryptoService.kt` | `src/services/e2eeService.ts` |
| RSA Crypto | `RSACrypto.kt` | `src/services/rsaCrypto.ts` |
| AES Crypto | `AESCrypto.kt` | `src/services/aesCrypto.ts` |
| PBKDF2 | `PBKDF2.kt` | `src/services/pbkdf2.ts` |
| Envelope | `EnvelopeEncryption.kt` | `src/services/envelopeEncryption.ts` |
| **Models** | | |
| Snippet | `Snippet.kt` | `src/models/snippet.ts` |
| Note | `Note.kt` | `src/models/note.ts` |
| Workspace | `Workspace.kt` | `src/models/workspace.ts` |
| Tag | `Tag.kt` | `src/models/tag.ts` |
| **UI** | | |
| ToolWindow | `SnipHiveToolWindowFactory.kt` | `src/views/sniphiveSidebar.ts` |
| Login Dialog | `LoginDialog.kt` | `src/views/dialogs/loginDialog.ts` |
| Create Snippet | `CreateSnippetDialog.kt` | `src/views/dialogs/createSnippetDialog.ts` |
| Panel Components | Various `*Panel.kt` | React/HTML views |
| **IDE Integration** | | |
| Actions | plugin.xml `<actions>` | `package.json` commands |
| Code Completion | `SnippetCompletionProvider.kt` | `CompletionItemProvider` |
| Status Bar | `SnipHiveStatusBarWidget.kt` | `StatusBarItem` |
| **Editors** | | |
| Snippet Editor | `SnippetEditorProvider.kt` | Custom editor provider |
| Note Editor | `NoteEditorProvider.kt` | Custom editor provider |

### 14.3 VSCode Extension Yapısı (Önerilen)

```
vscode-sniphive/
├── src/
│   ├── services/
│   │   ├── apiClient.ts
│   │   ├── authService.ts
│   │   ├── e2eeService.ts
│   │   ├── settings.ts
│   │   └── lookupService.ts
│   ├── crypto/
│   │   ├── rsaCrypto.ts
│   │   ├── aesCrypto.ts
│   │   ├── pbkdf2.ts
│   │   └── envelopeEncryption.ts
│   ├── models/
│   │   ├── snippet.ts
│   │   ├── note.ts
│   │   ├── workspace.ts
│   │   └── tag.ts
│   ├── views/
│   │   ├── sniphiveSidebar.ts
│   │   ├── snippetList.ts
│   │   ├── noteList.ts
│   │   ├── searchPanel.ts
│   │   └── dialogs/
│   │       ├── loginDialog.ts
│   │       ├── createSnippetDialog.ts
│   │       ├── insertSnippetDialog.ts
│   │       └── e2eeSetupDialog.ts
│   ├── commands/
│   │   ├── createSnippet.ts
│   │   ├── insertSnippet.ts
│   │   ├── refreshSnippets.ts
│   │   └── showRecent.ts
│   ├── completion/
│   │   └── snippetCompletion.ts
│   ├── extension.ts
│   └── types/
│       └── vscode.d.ts
├── package.json
├── tsconfig.json
└── README.md
```

### 14.4 VSCode'a Özgü Dikkat Noktaları

1. **Secret Storage**: VSCode'ın `vscode.secretStorage` API'sini kullan
2. **Webview**: Tool window için `WebviewPanel` kullan
3. **Commands**: Tüm aksiyonlar `registerCommand` ile kaydedilmeli
4. **TreeView**: Snippet/Note listesi için `TreeView` API
5. **CompletionItemProvider**: Dil-agnostik tamamlama için `provideCompletionItems`
6. **WorkspaceFolders**: Çalışma alanı yönetimi için `workspace.workspaceFolders`

### 14.5 API Tutarlılığı

VSCode versiyonu **aynı API endpoint'lerini** kullanmalıdır:
- Base URL: `https://api.sniphive.net` (yapılandırılabilir)
- Tüm endpoint'ler JetBrains versiyonu ile aynı

### 14.6 Kriptografi Tutarlılığı

VSCode versiyonu **aynı kriptografik standartları** kullanmalıdır:
- RSA-4096 OAEP
- AES-256-GCM
- PBKDF2 600,000 iterations
- Aynı JWK formatı
- Aynı envelope encryption formatı

---

## Ek: Dosya Yolları Referansı

| Dosya | Yol |
|-------|-----|
| Plugin Descriptor | `src/main/resources/META-INF/plugin.xml` |
| Build Config | `build.gradle.kts` |
| Main Plugin Class | `src/main/kotlin/com/sniphive/idea/SnipHivePlugin.kt` |
| API Client | `src/main/kotlin/com/sniphive/idea/services/SnipHiveApiClient.kt` |
| Auth Service | `src/main/kotlin/com/sniphive/idea/services/SnipHiveAuthService.kt` |
| Settings | `src/main/kotlin/com/sniphive/idea/config/SnipHiveSettings.kt` |
| E2EE Service | `src/main/kotlin/com/sniphive/idea/crypto/E2EECryptoService.kt` |
| Tool Window | `src/main/kotlin/com/sniphive/idea/toolwindow/SnipHiveToolWindowFactory.kt` |
| Market Desc | `DESCRIPTION.html` |
| README | `README.md` |

---

*Doküman versiyonu: 1.0.0*
*Son güncelleme: 2026-04-09*
