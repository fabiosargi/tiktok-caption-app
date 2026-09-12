plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fabio.tiktokcaption"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fabio.tiktokcaption"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    // Assinatura FIXA do debug (arquivo debug.keystore versionado no repo). Sem isso,
    // cada build no GitHub Actions roda numa maquina nova e o plugin do Android gera
    // uma chave de debug aleatoria diferente a cada vez -- ai o Android recusa
    // atualizar o app por cima (assinatura mudou) e obriga desinstalar antes de
    // instalar a versao nova, o que apaga TODOS os dados salvos (inclusive as chaves
    // de API do Gemini e do Post for Me). Com uma chave fixa, a instalacao nova
    // sempre atualiza por cima e as chaves salvas nunca mais somem.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // PickVisualMedia (seletor de foto/video do sistema, sem precisar de permissao
    // de armazenamento) -- usado pra escolher um video manual da galeria.
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Tratamento leve de qualidade de video (contraste/saturacao) direto no
    // aparelho, rodando em paralelo com a geracao da legenda pela IA.
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")

    // Deteccao de rosto 100% no aparelho (sem IA generativa, sem custo de API) --
    // usada so pra escolher analiticamente o melhor instante do video pra servir
    // de capa no TikTok (olhos abertos, sem careta no meio de uma fala).
    implementation("com.google.mlkit:face-detection:16.1.7")
}
