# sidehustle-daily-title

每天（或手动）从 Reddit / Hacker News / V2EX / Freelancer / GitHub Issues / Stack Exchange / Bluesky 抓公开讨论，去重写入 SQLite，用兼容 OpenAI 的 LLM（默认 DeepSeek）判断是否为软件商机，再生成一份中文 Markdown 日报。

只读、只分析、不回帖、不投标。报告默认仅自用。

## 能做什么

1. **抓帖**：关键词搜索 + 去重（`source + id`）
2. **分类**：LLM 输出结构化 JSON（是否商机、痛点标签、人群、付费证据、1–5 分、中文摘要）
3. **聚合**：同一 `pain_key` 在 14 天内出现 ≥ 3 次会进「反复出现的痛点」表
4. **报告**：`reports/YYYY-MM-DD.md` 和 `reports/latest.md`

Upwork RSS 已下线、官方 API 不允许当 feed 用；Fiverr 买家需求不公开。这两处用 **Freelancer 公开项目 API** 代替「有人正在雇人做重复活」的信号。

## 环境

- JDK 21+
- Maven 3.9+
- 大陆本机跑 Reddit / HN 通常需要 `HTTPS_PROXY`；更省事的方式是丢给 GitHub Actions（美国节点）

## 用 OrbStack 运行（推荐）

本机 Docker 上下文切到 OrbStack 后，在本文件夹执行：

```bash
cd sidehustle_daliy_title
cp .env.example .env   # 已有 .env 可跳过
mvn -q -DskipTests package
docker compose up -d --build
```

浏览器打开 http://127.0.0.1:8080/ 。`data/`、`reports/`、`config.json` 和 `.env` 挂在本机，重启不丢。密钥不进镜像。改代码后重复 `mvn package` 再 `docker compose up -d --build`。

Docker Hub 拉不动时：

```bash
JAVA_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre \
docker compose up -d --build
```

大陆访问 Reddit / HN 时，在 `.env` 里写本机代理，例如 `HTTPS_PROXY=http://127.0.0.1:7890`。容器启动时会把其中的 `127.0.0.1` 换成 `host.docker.internal`，才能走到 OrbStack 宿主机上的代理。

```bash
docker compose logs -f app
docker compose restart app
docker compose down
```

不要同时再用 IDE 占 8080。

## 本地运行

数据在项目内的 SQLite 文件 `data/sidehustle.db`，不需要申请远程数据库。

```bash
cd sidehustle_daliy_title
cp .env.example .env   # 填密钥
mvn -q -DskipTests package
java -jar target/sidehustle-daily-title.jar
```

浏览器打开 http://localhost:8080 ，帖子按北京时间日期分组。页面上的「重新抓取」会再拉一轮公开源。

命令行仍然可用（跑完即退出，不启动网页）：

```bash
java -jar target/sidehustle-daily-title.jar fetch
```

子命令：

| 命令 | 作用 |
|---|---|
| `run`（默认） | 抓取 + 分类 + 写报告 |
| `fetch` | 只抓帖入库 |
| `classify` | 只对未分类帖跑 LLM |
| `report` | 用库里已有数据重出最近 24 小时报告 |

```bash
java -jar target/sidehustle-daily-title.jar fetch --config config.json
```

密钥来自环境变量或项目根目录 `.env`（环境变量优先）。**不要把 `.env` 提交进 GitHub**：DeepSeek（`LLM_API_KEY`）、Reddit、V2EX、飞书 webhook 以及其它抓取站的密钥都只放本机 `.env` 或仓库的 GitHub Secrets。仓库里只有空的 `.env.example`。

| 变量 | 必须 | 说明 |

| 变量 | 必须 | 说明 |
|---|---|---|
| `REDDIT_CLIENT_ID` / `REDDIT_CLIENT_SECRET` / `REDDIT_USER_AGENT` | Reddit 源 | [reddit.com/prefs/apps](https://www.reddit.com/prefs/apps) 建一个 app |
| `LLM_API_KEY` | 分类 | DeepSeek / Qwen / Kimi / OpenAI 等，只要暴露 `/chat/completions` |
| `V2EX_TOKEN` | V2EX 源 | [v2ex.com/settings/tokens](https://www.v2ex.com/settings/tokens) |
| `GITHUB_TOKEN` | 可选 | 公开 Issues 搜索不用申请应用。令牌只提高限额；Actions 里自动有 |
| `STACKEXCHANGE_KEY` | 可选 | 不填也能抓，大约每天 300 次。要更高额度再去 [stackapps.com](https://stackapps.com/apps/oauth/register) 注册一个 key |
| `FEISHU_WEBHOOK` | 可选 | 飞书自定义机器人，推送报告摘要 |
| `HTTPS_PROXY` | 可选 | 本机翻墙，如 `http://127.0.0.1:7890` |
| `LLM_BASE_URL` / `LLM_MODEL` | 可选 | 覆盖 `config.json` 里的默认 DeepSeek |

没有填的源会被跳过，其余源照常跑。

## GitHub Actions（推荐）

把 **本目录当成独立 git 仓库**（或把 `.github/workflows/daily.yml` 拷到仓库根，并加上 `defaults.run.working-directory: sidehustle_daliy_title`）。

仓库 Secrets：

- `REDDIT_CLIENT_ID`
- `REDDIT_CLIENT_SECRET`
- `REDDIT_USER_AGENT`
- `LLM_API_KEY`
- `V2EX_TOKEN`（可选）
- `FEISHU_WEBHOOK`（可选）

默认每天 22:00 UTC（北京时间次日 06:00）跑一次，也可在 Actions 页手动 `workflow_dispatch`。跑完会把 `data/` 和 `reports/` commit 回仓库，这样跨天的痛点计数才能累计。

## 配置

`config.json` 里改关键词、subreddit、节点、评分阈值。Reddit 默认是「每个 sub × 每个关键词搜一次 + 拉 `/new` 本地过滤」，请求较多但在免费 100 QPM 内。想缩短时间就删掉用不到的 sub 或关键词。

## 产出示例结构

```
reports/2026-10-13.md
  ## 一、反复出现的痛点
  ## 二、本次新发现的商机
  ## 三、数据源统计
```

## 合规

- Reddit Data API：个人非商业使用、OAuth、描述性 User-Agent、遵守 100 QPM。把报告卖给别人需要另签协议。
- 各源均为公开只读接口，不做爬虫、不做自动回复。
