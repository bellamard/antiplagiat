# Antiplagiat

Application Spring Boot pour l’upload, l’analyse antiplagiat/IA, la vectorisation sémantique et la génération de rapports de documents académiques.

## 1. Documentation technique

### Architecture générale

Le système suit ce flux:

```text
Upload document
→ stockage disque + stockage DB gzip/Base64
→ extraction texte avec Apache Tika
→ OCR si le texte est vide/faible
→ analyse Python
→ découpage sémantique
→ vectorisation pgvector
→ sauvegarde AnalysisHistory
→ synchronisation Score
→ génération Report
```

### Stack

- Java 17
- Spring Boot 4.1
- Spring Security + JWT
- Spring Data JPA
- PostgreSQL
- pgvector
- Apache Tika
- Python
- sentence-transformers
- Tesseract OCR ou PaddleOCR optionnel

### Modules principaux

- `DocumentsController` / `DocumentService`
  - upload;
  - téléchargement;
  - suppression;
  - stockage Base64 compressé;
  - stockage disque dans `uploads/documents`.

- `PythonPlagiarismDetector`
  - extraction depuis Base64 ou fichier disque;
  - appel du script Python;
  - gestion OCR;
  - parsing robuste du JSON Python.

- `ai_analyzer.py`
  - nettoyage texte;
  - segmentation en phrases;
  - découpage sémantique;
  - similarité lexicale;
  - similarité sémantique;
  - vectorisation;
  - stockage pgvector.

- `AnalysisService`
  - création manuelle d’une analyse via matriculation;
  - sauvegarde dans `analysis_history`;
  - synchronisation de `Score`.

- `ReportService`
  - génération d’un rapport depuis `analysisId` ou `documentId`;
  - récupération de la dernière analyse du document;
  - option de vidage du Base64 après rapport.

### Stockage des documents

À l’upload, le fichier est conservé de deux façons:

1. En base de données:
   - champ `Document.compressedBase64Content`;
   - contenu compressé avec gzip puis encodé Base64.

2. Sur disque:
   - dossier configuré par `app.documents.storage-dir`;
   - par défaut: `uploads/documents`;
   - nom: `{uuid}.{extension}`.

Cette double conservation permet de vider le Base64 après génération d’un rapport sans perdre la possibilité de télécharger ou réanalyser le fichier.

### Analyse antiplagiat et IA

L’analyse se lance:

- automatiquement après `POST /api/documents`;
- manuellement via `POST /api/histories`.

Le résultat est sauvegardé dans:

- `analysis_history.overallScore`;
- `analysis_history.aiScore`;
- `analysis_history.details`;
- table `Score`, synchronisée avec le dernier résultat.

Après analyse, le statut du score est `COMPLETED`.

### OCR

OCR utilisé quand:

- le document est une image;
- le document est un PDF dont le texte extrait par Tika est trop faible.

Formats acceptés:

```text
pdf, doc, docx, txt, png, jpg, jpeg, tif, tiff, bmp, gif, webp
```

Par défaut:

- Tesseract est privilégié;
- PaddleOCR est désactivé pour éviter les téléchargements lents de modèles.

Pour activer PaddleOCR:

```powershell
$env:ANALYSIS_USE_PADDLEOCR="true"
```

### pgvector

Le script Python découpe le texte en chunks sémantiques puis sauvegarde les embeddings dans PostgreSQL avec pgvector.

Table par défaut:

```properties
analysis.pg.table=embeddings
```

Structure logique:

```text
document_id
chunk_index
chunk_hash
chunk_text
embedding vector(...)
created_at
```

Configuration:

```properties
analysis.pg.enabled=true
analysis.pg.uri=${PG_URI:postgresql://postgres:password@localhost:5432/antiplagiat}
analysis.pg.table=embeddings
analysis.pg.chunk-max-chars=1200
analysis.pg.chunk-overlap-sentences=1
```

Préparer PostgreSQL:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### Configuration principale

Fichier:

```text
src/main/resources/application.properties
```

Variables importantes:

```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/antiplagiat}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:password}

app.documents.storage-dir=uploads/documents

spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=110MB

analysis.python.executable=python
analysis.python.script=src/main/resources/python/ai_analyzer.py
analysis.python.timeout.seconds=120
analysis.python.model=all-MiniLM-L6-v2
analysis.ocr.min-text-length=80
```

### Dépendances Python

Installer:

```powershell
pip install -r src/main/resources/python/requirements.txt
```

Pour OCR Tesseract, installer aussi Tesseract sur la machine et l’ajouter au `PATH`.

### Build et lancement

Compiler:

```powershell
.\mvnw.cmd -DskipTests compile
```

Lancer:

```powershell
.\mvnw.cmd spring-boot:run
```

URL locale:

```text
http://localhost:8080
```

## 2. Diagramme d’action utilisateur

Ce diagramme décrit ce que l’utilisateur doit faire pour obtenir un résultat fiable: document enregistré, analyse complète, score disponible et rapport généré.

```text
1. Préparer l’environnement
   ↓
2. Se connecter et obtenir un token JWT
   ↓
3. Uploader le document avec toutes les métadonnées
   ↓
4. Attendre la fin de l’analyse automatique
   ↓
5. Vérifier le score et l’historique d’analyse
   ↓
6. Générer le rapport
   ↓
7. Télécharger/consulter le rapport
   ↓
8. Optionnel: vider le Base64 après rapport
```

### Étape 1 — Préparer l’environnement

Action utilisateur:

- démarrer PostgreSQL;
- vérifier que la base `antiplagiat` existe;
- vérifier que pgvector est activé;
- installer les dépendances Python;
- installer Tesseract si les documents scannés doivent être analysés;
- démarrer Spring Boot.

Commandes utiles:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

```powershell
pip install -r src/main/resources/python/requirements.txt
.\mvnw.cmd spring-boot:run
```

Résultat attendu:

- l’API répond sur `http://localhost:8080`;
- les rôles et utilisateurs de test sont créés par le seeder;
- la table pgvector `embeddings` est prête à recevoir les chunks.

### Étape 2 — Se connecter

Action utilisateur:

- appeler `POST /api/users/login`;
- appeler `POST /api/users/login/verify`;
- récupérer le token JWT.

Résultat attendu:

- le client possède un header valide:

```http
Authorization: Bearer <token>
```

Sans ce token, les routes protégées retournent `403 Forbidden`.

### Étape 3 — Uploader le document

Action utilisateur:

- envoyer le fichier via `POST /api/documents`;
- renseigner les métadonnées obligatoires:
  - `file`;
  - `name`;
  - `faculty`;
  - `department`;
  - `author`;
  - `yearOfAcademic`;
  - `matriculation`.

Ce que le backend fait:

```text
Réception multipart
→ validation extension/taille
→ sauvegarde fichier disque
→ compression gzip + encodage Base64
→ insertion table document
→ lancement analyse antiplagiat/IA
```

Résultat attendu:

- une ligne est créée dans `document`;
- le fichier est disponible dans `uploads/documents`;
- `compressedBase64Content` contient le fichier compressé;
- l’analyse démarre automatiquement.

### Étape 4 — Analyse automatique

Ce que le backend fait:

```text
Lecture document
→ extraction texte avec Tika
→ si texte faible: OCR
→ nettoyage texte
→ découpage en phrases
→ découpage en chunks sémantiques
→ génération embeddings
→ stockage pgvector
→ calcul score antiplagiat
→ calcul indicateur IA
```

Cas PDF scanné:

```text
PDF image/scanné
→ rendu page par page avec PyMuPDF
→ OCR Tesseract/PaddleOCR
→ reconstruction texte
→ analyse normale
```

Configuration importante:

```properties
analysis.ocr.max-pages=200
analysis.ocr.workers=4
analysis.python.timeout.seconds=900
analysis.pg.chunk-max-chars=1200
```

Résultat attendu:

- une ligne est créée dans `analysis_history`;
- une ligne est créée ou mise à jour dans `score`;
- les chunks sont stockés dans `embeddings`;
- le statut du score passe à `COMPLETED`.

### Étape 5 — Vérifier le résultat

Action utilisateur:

- consulter les documents avec `GET /api/documents`;
- consulter l’historique avec `GET /api/histories`;
- consulter le score avec `GET /api/scores/document/{documentId}`.

Résultat attendu:

- le document apparaît dans la liste;
- l’historique contient `overallScore`, `aiScore` et `details`;
- le score associé au document est disponible.

Si le score est absent, vérifier:

- que l’analyse Python n’a pas dépassé le timeout;
- que les dépendances Python sont installées;
- que le document contient du texte ou que l’OCR fonctionne;
- que PostgreSQL/pgvector est accessible.

### Étape 6 — Générer le rapport

Action utilisateur:

- générer le rapport depuis une analyse:

```json
{
  "analysisId": "UUID_ANALYSE"
}
```

- ou depuis un document:

```json
{
  "documentId": "UUID_DOCUMENT"
}
```

Ce que le backend fait:

```text
Recherche analyse
→ récupération document
→ récupération score/détails
→ construction contenu rapport
→ insertion table reports
```

Résultat attendu:

- une ligne est créée dans `reports`;
- le rapport contient les informations du document, les scores et les détails d’analyse.

### Étape 7 — Consulter le rapport

Action utilisateur:

- lister les rapports avec `GET /api/reports`;
- ouvrir un rapport avec `GET /api/reports/{id}`.

Résultat attendu:

- l’utilisateur retrouve le rapport généré;
- le rapport est lié au document et à l’analyse.

### Étape 8 — Vider le Base64 après rapport

Action utilisateur:

```json
{
  "documentId": "UUID_DOCUMENT",
  "clearBase64Content": true
}
```

Ce que le backend fait:

```text
Génération rapport
→ suppression compressedBase64Content
→ contentCompressed=false
→ storedSize=0
→ conservation fichier disque
```

Résultat attendu:

- le rapport reste disponible;
- la base de données est allégée;
- le téléchargement reste possible depuis `uploads/documents`.

### Résumé opérationnel

| Étape | Action utilisateur | Résultat attendu |
| --- | --- | --- |
| 1 | Démarrer PostgreSQL, pgvector, Python, Spring Boot | API prête |
| 2 | Se connecter | Token JWT obtenu |
| 3 | Uploader document | Document stocké |
| 4 | Attendre analyse | Historique + score + embeddings |
| 5 | Vérifier score/historique | Résultat contrôlé |
| 6 | Générer rapport | Rapport créé |
| 7 | Consulter rapport | Rapport disponible |
| 8 | Vider Base64 si besoin | DB allégée |

## 3. Manuel d’utilisation API

### Authentification

Le système utilise JWT.

Étape 1 — démarrer la connexion:

```http
POST /api/users/login
```

Body:

```json
{
  "username": "student1"
}
```

Étape 2 — vérifier le code:

```http
POST /api/users/login/verify
```

Body:

```json
{
  "identifier": "...",
  "username": "student1",
  "code": "123456"
}
```

Ensuite utiliser le token:

```http
Authorization: Bearer <token>
```

### Uploader un document

```http
POST /api/documents
Content-Type: multipart/form-data
```

Champs requis:

```text
file
name
faculty
department
author
yearOfAcademic
matriculation
```

Champs optionnels:

```text
director
rapporteur
academic
```

Exemple `curl`:

```bash
curl -X POST http://localhost:8080/api/documents \
  -H "Authorization: Bearer <token>" \
  -F "file=@memoire.pdf" \
  -F "name=Mémoire de test" \
  -F "faculty=Sciences" \
  -F "department=Informatique" \
  -F "author=Jean Test" \
  -F "yearOfAcademic=2025-2026" \
  -F "matriculation=DOC-001"
```

Effet de l’upload:

- sauvegarde du document;
- analyse automatique;
- création d’un historique;
- création ou mise à jour du score;
- vectorisation pgvector si configurée.

### Lister les documents

```http
GET /api/documents
```

### Obtenir un document

```http
GET /api/documents/{id}
```

### Télécharger un document

```http
GET /api/documents/{id}/download
```

Le téléchargement utilise:

1. le Base64 en base si disponible;
2. sinon le fichier disque dans `uploads/documents`.

### Supprimer un document

```http
DELETE /api/documents/{id}
```

### Lancer une analyse manuelle

```http
POST /api/histories
```

Body:

```json
{
  "matriculation": "DOC-001"
}
```

Cette route est utile pour réanalyser un document existant.

### Lister les historiques

```http
GET /api/histories
```

### Obtenir un historique

```http
GET /api/histories/{id}
```

### Consulter les scores

Lister:

```http
GET /api/scores
```

Score par document:

```http
GET /api/scores/document/{documentId}
```

La création manuelle d’un score est interdite. Les scores sont produits par l’analyse.

### Générer un rapport avec une analyse

```http
POST /api/reports
```

Body:

```json
{
  "analysisId": "UUID_ANALYSE"
}
```

### Générer un rapport avec un document

Le service prend la dernière analyse du document.

```json
{
  "documentId": "UUID_DOCUMENT"
}
```

### Générer un rapport et vider le Base64

```json
{
  "documentId": "UUID_DOCUMENT",
  "clearBase64Content": true
}
```

Effet:

- le rapport est généré;
- `compressedBase64Content` est vidé;
- `storedSize` passe à `0`;
- le fichier disque reste disponible.

### Lister les rapports

```http
GET /api/reports
```

### Obtenir un rapport

```http
GET /api/reports/{id}
```

## 4. Codes d’erreur fréquents

- `400 Bad Request`
  - champ obligatoire absent;
  - type de fichier non autorisé;
  - requête rapport sans `analysisId` ni `documentId`.

- `403 Forbidden`
  - l’utilisateur tente d’accéder à un document ou rapport qui ne lui appartient pas.

- `404 Not Found`
  - document, analyse ou rapport introuvable.

- `409 Conflict`
  - tentative de création manuelle de score interdite.

- `413 Payload Too Large`
  - fichier supérieur à la limite configurée.

## 5. Checklist de fonctionnement

Avant de tester:

1. PostgreSQL est lancé.
2. La base `antiplagiat` existe.
3. L’extension pgvector est disponible.
4. Les dépendances Python sont installées.
5. Le modèle `sentence-transformers` est disponible localement ou l’accès réseau est autorisé.
6. Tesseract est installé si OCR requis.
7. Spring Boot est redémarré après modification de `application.properties`.

## 6. Fichiers utiles

- `src/main/java/com/b2la/antiplagiat/service/DocumentService.java`
- `src/main/java/com/b2la/antiplagiat/analysis/infrastructure/PythonPlagiarismDetector.java`
- `src/main/java/com/b2la/antiplagiat/analysis/application/AnalysisService.java`
- `src/main/java/com/b2la/antiplagiat/service/ReportService.java`
- `src/main/resources/python/ai_analyzer.py`
- `src/main/resources/application.properties`
- `src/main/resources/antiplagiat-with-auth.postman_collection.json`
