<div align="center">

<img src="docs/media/logo.png" alt="Nuoyi" width="88" />

# 诺伊 · Nuoyi

**An AI assistant on your phone: say what you need, and it works your apps and the web until it's done.**

It works on a hidden background screen, so you can keep using your phone.

<img src="https://img.shields.io/badge/Android-11%2B-2ea043?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+" />
<a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-lightgrey" alt="license" /></a>

<br/>

诺伊是一款可以**免手唤起**的手机 AI 助理：喊「诺伊」，或用系统数字助手叫起，
说出要办的事，它会在后台屏幕上把应用和网页操作完，再把结果念给你听。
别人问「你是谁」，它只回答一句「我是李万禧的女儿，数字女儿」。

公版用系统数字助手入口、唤醒词固定；自用版支持自定义唤醒词与本地常驻热词。

<br/>

English · [简体中文](docs/readme/README.zh-CN.md) · [繁體中文](docs/readme/README.zh-Hant.md) · [日本語](docs/readme/README.ja.md) · [한국어](docs/readme/README.ko.md) · [Español](docs/readme/README.es.md)

<br/>

[Official site & downloads](https://noy.127068.xyz/) · [Source & releases](https://github.com/cnwhli/nuoyi) · [Issues](https://github.com/cnwhli/nuoyi/issues/new)

<br/>

<img src="docs/media/wechat-send-side.gif" alt="From one sentence, Nuoyi opens WeChat on its background screen, finds the contact and sends the message" width="640" />

<sub>"Send a message to File Transfer": Nuoyi's chat on the left, each step on its background screen on the right (4× speed; chat list blurred)</sub>

</div>

## Get things done with one sentence

<table>
<tr>
<td align="center" width="33%"><img src="docs/media/wechat-group.gif" width="230" alt="Reads through a WeChat group and summarizes today's chat" /><br/><b>"What did the group talk about today?"</b><br/><sub>Scrolls through the chat in the background, then sums it up</sub></td>
<td align="center" width="33%"><img src="docs/media/amazon-cart.gif" width="230" alt="Picks a charger on a shopping site and adds it to the cart" /><br/><b>"A charger under $30, rated 4.5 stars or more"</b><br/><sub>Adds the pick to the cart and stops before checkout</sub></td>
<td align="center" width="33%"><img src="docs/media/xhs-summary.gif" width="230" alt="Summarizes a creator's recent posts" /><br/><b>"What has this creator posted lately?"</b><br/><sub>Opens the app, reads post by post, then sums it up</sub></td>
</tr>
</table>

<details>
<summary>More demos</summary>

<table>
<tr>
<td align="center" width="33%"><img src="docs/media/boss-greet.gif" width="230" alt="Greets recruiters for 3 jobs on a job site" /><br/>Greets recruiters for 3 jobs on a job site</td>
<td align="center" width="33%"><img src="docs/media/google-flights.gif" width="230" alt="Finds the cheapest nonstop flight" /><br/>Finds the cheapest nonstop from San Francisco to New York</td>
<td align="center" width="33%"><img src="docs/media/hacker-news.gif" width="230" alt="Reads out the top 5 stories and their points" /><br/>Reads out the top 5 stories on a news site</td>
</tr>
<tr>
<td align="center"><img src="docs/media/job-greet.jpg" width="230" alt="Finds jobs and greets recruiters" /><br/>Finds jobs and greets recruiters</td>
<td align="center"><img src="docs/media/music-vip.jpg" width="230" alt="Watches ads to unlock music perks" /><br/>Watches ads to unlock music perks</td>
<td align="center"><img src="docs/media/food-order.jpg" width="230" alt="Orders takeout with stacked discounts and asks you before paying" /><br/>Orders takeout with stacked discounts, asks before paying</td>
</tr>
</table>

</details>

<sub>Several demos use apps popular in China: WeChat is the main messaging app there, and Xiaohongshu (RED) is a lifestyle and social platform. Nuoyi isn't tied to them; it works with any app or website on your phone.</sub>

## Why Nuoyi

| | |
|---|---|
| 📱 **Leaves your screen alone** | Opens apps, taps, types and scrolls on a separate background screen while you use your phone as usual; tap the small live view whenever you want to watch |
| 🧩 **Works with any app** | Looks at the screen and finds buttons the way a person does: no app APIs, no per-app integration |
| 🌐 **Call it from any web page** | In the built-in browser, say what you want on the page you're reading and it works right there; touch the page and it stops |
| 🧠 **Gets better with use** | Remembers the preferences you tell it, and turns tasks it finishes into skills so next time is faster and steadier |
| 🔒 **Your data stays on your phone** | There is no Nuoyi server; use your own model API key, or sign in with a ChatGPT, xAI or OpenRouter account |
| 🆓 **Open source and free** | Apache-2.0, all code public |

## Get started in three steps

1. **Install**: download the signed 诺伊 APK from the [official site](https://noy.127068.xyz/download/) (or its [GitHub Releases](https://github.com/cnwhli/nuoyi/releases)) and check the SHA-256 on that page first. Do not install APKs from the upstream Nuoyi repository — that is a different app with a different signature.
2. **Connect a model**: in Settings › Model, pick a provider and enter an API key, or just sign in with an account ([models and sign-in](docs/models.en.md)).
3. **To operate phone apps**: follow the "Set up phone control" wizard in Settings and pair wireless debugging on the phone once ([setup guide](docs/setup-guide.en.md)). Skip this if you only want chat, web and files.

Then try saying: "Set alarms for 9:40 and 10:00 tomorrow morning" or "Check the weather at 8 every morning and tell me only if it will rain".

> 💡 Have a computer handy? Send this to the AI agent on it and it will set everything up for you:<br/>
> `Follow https://github.com/cnwhli/nuoyi/blob/main/docs/install-with-agent.en.md to install Nuoyi on the Android phone connected over USB.`

## What it can do

| | |
|---|---|
| **Operate apps** | Reads long lists and chat histories, taps and types, shares text into apps; hands logins, verification codes and payments to you |
| **Browse the web** | A built-in desktop-class browser that keeps you signed in, reads pages, downloads and uploads; paces itself and hands block pages to you |
| **Your current screen** | Double-tap the floating capsule to send the screen you're looking at: "how should I reply?", "note this down" |
| **Schedules and conditions** | "Remind me at 9 tomorrow", "do this every Friday"; runs on time in the original conversation |
| **Jump in or stop anytime** | Keep talking while a task runs to add or correct things (long-press to withdraw or edit one Nuoyi hasn't picked up yet); after a stop it reports where it got to, and "continue" picks up from there |
| **Hold to talk** | Hold the chat input to speak and edit the words before sending; long-press the floating capsule to speak and release to send. Uses the phone’s built-in speech recognition, or a provider’s speech model if it has none |
| **Files and computing** | A Linux sandbox per conversation runs Python, Node and shell on the files you send (arm64) |
| **From your computer** | AI tools on your computer can hand Nuoyi tasks through CLI + Skill or MCP ([use Nuoyi from a computer](docs/computer.en.md)) |

By default Nuoyi runs in **Ask mode**: it checks with you only before spending money (placing orders, paying) and just does everything else. You can switch to **Autonomous mode** instead. See [Ask and Autonomous](docs/modes.en.md).

For how each of these works and the finer details, see [features in detail](docs/features.en.md).

<details>
<summary>Apps and websites with built-in usage knowledge (anything not listed still works)</summary>

The names are shown as they appear in the knowledge files.

<!-- generated:knowledge:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
| Category | Built in |
|---|---|
| **Apps** | Android 设置, 百家号, 哔哩哔哩, BOSS直聘, Chrome, 得到, 抖音, 飞书, Gmail, 即刻, 快手, 领英, QQ邮箱, Telegram, 今日头条, Uber, Uber Eats, 微信, 微博, WhatsApp, X, 闲鱼, 小红书, 知乎 |
| **Websites** | Amazon, 哔哩哔哩, Google Calendar, 豆瓣, 抖音, Instagram, Gmail, Google Maps, Telegram, 百度贴吧, 微博, 搜狗微信, WhatsApp, 小红书, 小宇宙, YouTube, 知乎, BOSS直聘 |
| **How-to skills** | 改 Nuoyi 自己的设置, 把内容整理成文字, 处理 PDF 文档, 查清一件事, 消息与邮件, 写技能 |
<!-- generated:knowledge:end -->

</details>

<details>
<summary>Supported model providers</summary>

<!-- generated:providers:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
Presets for DeepSeek, 通义千问, Kimi, 智谱, 智谱 Coding Plan, 硅基流动, 火山方舟, MiniMax, 阶跃星辰, 腾讯混元, 百度千帆, OpenRouter, Requesty, OpenCode Go, Anthropic, Gemini, OpenAI, xAI, Mistral, Groq, Ollama, LM Studio and more; for any other provider pick Custom (自定义), which takes any OpenAI-compatible, Anthropic, Gemini or OpenAI Responses endpoint.
<!-- generated:providers:end -->

You can also skip the key and sign in with a ChatGPT (Plus / Pro), xAI / Grok or OpenRouter account. Steps for adding a model, and what to know about each sign-in, are in [models and sign-in](docs/models.en.md).

</details>

## Which phones

| | Requirement |
|---|---|
| Chat, web, files | Android 11 or later |
| Operating phone apps | Android 12 or later; **Android 14+ recommended** (the background screen has its own focus, so it doesn't get in the way of your typing on the main screen) |
| Linux sandbox | arm64 devices |

Not sure your phone is up to it? Run Settings › Recovery & repair › **Compatibility self-check**: 12 checks in about a minute, and you can [send us the results](https://github.com/cnwhli/nuoyi/issues/new?template=device-report.yml) in one tap.

## Privacy & safety

- **There is no Nuoyi server**: what you say and what's on your screen go only to the model provider you set up.
- API keys and memories are stored encrypted on the phone; conversations and run records stay on the device; update checks contact the official site and its own GitHub mirror only.
- Operating third-party apps and websites automatically may break their terms of service, so use your own judgment. For anything important, use Ask mode and check the results. See the [disclaimer](docs/disclaimer.en.md).

<details>
<summary><b>FAQ</b></summary>

**Wireless debugging turned itself off?** Android turns it off when you change Wi-Fi or restart. With your permission, Nuoyi turns it back on by itself; if it still can't connect, go back to "Set up phone control" and pair again.

**Can't find wireless debugging on my phone?** Some phone systems leave it out. You can start the phone helper from a computer over USB instead; see the [setup guide](docs/setup-guide.en.md).

**Some screens can't be read or captured?** Pages like payments and password entry block screenshots, so Nuoyi asks you to take over there.

**Can I use a model running on my own computer?** Yes: pick "Custom" as the provider and enter its address on your local network.

**Is the app in English?** Yes. The app UI comes in English, Arabic, Simplified Chinese, Traditional Chinese, Japanese, Korean, Spanish, Portuguese, Russian and Indonesian, follows your system language, and can be switched in Settings › Language. The built-in app and website knowledge is written in Chinese, but that doesn't limit you: the model reads it fine, and you can talk to Nuoyi in English.

**Where do updates come from?** The [official site](https://noy.127068.xyz/) publishes the version manifest and the checksums; APK files are hosted on this repository's Releases. The app verifies the download host, size, SHA-256, package name, version and signing certificate before installing. If the site is unreachable it reads the same manifest from the repository mirror.

More answers are in the [FAQ](docs/faq.en.md).

</details>

## 来源与许可 / Origin & license

诺伊基于 [Nuoyi](https://github.com/wangzan101/Mika)（Apache-2.0, Copyright 2026 wangzan101）
二次开发，与上游项目无隶属或背书关系。上游的 [LICENSE](LICENSE)、[NOTICE](NOTICE) 与
[第三方组件声明](licenses/THIRD_PARTY_NOTICES.md) 一并保留；上游文档见
[docs index](docs/README.en.md)，问题反馈请到 [本仓库 Issues](https://github.com/cnwhli/nuoyi/issues/new)。

## Star History

以下数据属于上游项目 [Nuoyi](https://github.com/wangzan101/Mika)，仅作来源致谢，不代表诺伊的下载量或排名。

<a href="https://star-history.com/#wangzan101/Mika&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=wangzan101/Mika&type=Date&theme=dark&v=20261107-2304" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=wangzan101/Mika&type=Date&v=20261107-2304" width="600" />
  </picture>
</a>

## Contributors

感谢 [Nuoyi 上游项目的贡献者](https://github.com/wangzan101/Mika/graphs/contributors)，
诺伊的语音、额度路由与品牌改造都建立在他们的工作之上。

## License

[Apache-2.0](LICENSE) · [NOTICE](NOTICE) · [Third-party components](licenses/THIRD_PARTY_NOTICES.md)
