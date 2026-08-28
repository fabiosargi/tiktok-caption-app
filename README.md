# App de legenda automática (TikTok / Instagram / YouTube)

## O que o app faz
1. Ao abrir, já busca sozinho o **último vídeo da sua galeria**.
2. Você toca em **"Gerar legenda com IA"** — o app manda esse vídeo pro Gemini e recebe de volta uma legenda longa + 5 hashtags, baseada no que está no vídeo.
3. Aparecem 3 botões: **TikTok**, **Instagram**, **YouTube**. Ao tocar em qualquer um, o app abre aquele aplicativo já com o vídeo anexado (usando o "Compartilhar" padrão do Android — o mesmo mecanismo que a Galeria usa).
4. Um serviço de Acessibilidade, rodando em segundo plano, detecta a tela de descrição desses apps e **cola a legenda sozinho**. A legenda também fica na área de transferência como reforço, caso precise colar manualmente em algum caso.
5. Você confere e aperta o **Publicar/Postar real dentro de cada app** — de propósito, não automatizei esse último toque.

## O que ainda precisa de teste no seu celular (sendo direto sobre isso)
- **TikTok**: testei contra uma gravação de tela que você me mandou — a lógica bate com a tela real (campo de texto único no topo). Confiança alta.
- **Instagram**: usei o mecanismo padrão de "Compartilhar" (sem cadastrar um app no Meta for Developers, o que exigiria aprovação deles). Isso deve abrir a tela de compartilhamento do Instagram, mas ele pode te perguntar se quer postar em Feed, Stories ou Reels antes de chegar na tela de legenda — não vai direto pro Reels como um app "oficialmente registrado" no Meta iria. Se quiser esse atalho direto pra Reels depois, dá pra evoluir, mas exige cadastro e revisão do Meta.
- **YouTube**: implementei com o mesmo padrão, mas não tenho como confirmar se o app do YouTube ainda aceita vídeo compartilhado assim (documentação recente sobre isso é escassa). Teste esse antes de confiar nele.
- Nos três casos, o "encontrar o campo de legenda e colar sozinho" é uma heurística (pega o primeiro campo de texto editável da tela) — funciona bem quando a tela só tem um campo assim, que é o caso do TikTok. Se em algum app não colar sozinho, a legenda já está copiada — é só colar manualmente enquanto ajustamos.

## Como compilar (via GitHub Actions — não precisa de Android Studio)
1. Esse repositório já está pronto: o workflow em `.github/workflows/build-apk.yml` compila sozinho a cada push (ou clique em "Run workflow" na aba Actions).
2. Quando terminar (~3-5 min), baixe o arquivo `app-debug-apk` na aba **Actions** do repositório → clique na execução → seção **Artifacts**.
3. Esse `.zip` contém o `app-debug.apk` — extraia e transfira pro celular.

## Como instalar no celular
1. Transfira o `app-debug.apk` pro celular (Google Drive, WhatsApp Web, cabo, etc.).
2. Toque nele — o Android vai pedir permissão pra "instalar de fontes desconhecidas" na primeira vez. Aceite.
3. Abra o app **"Legenda TikTok"**.

## Configuração inicial (uma vez só)
1. Toque no ícone de engrenagem (canto superior direito) e cole sua **chave de API do Gemini** (Google AI Studio) — ela fica guardada só no seu celular, nunca sai daí a não ser pra chamar a API do Google.
2. Ainda nessa tela, toque em **"Abrir configurações de Acessibilidade"** e ative o serviço **"Legenda TikTok"** — é isso que permite colar a legenda sozinho.
3. Conceda a permissão de acesso a vídeos/mídia quando pedido.

## Manutenção esperada
- Se o TikTok/Instagram/YouTube atualizarem e mudarem a tela de legenda de um jeito que o campo de texto não seja mais "óbvio" (o único editável na tela), a colagem automática pode parar de funcionar pra aquele app específico — a legenda continua indo pra área de transferência normalmente, então colar manualmente sempre funciona como reserva.
