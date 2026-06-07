# AI Translation Assistant

AI 翻译助手，采用前后端分离的桌面应用架构。

## 技术栈

- 后端：Java 17、Spring Boot、Maven
- 前端：Electron、Vue 3、Vite、TypeScript、Pinia

## 目录结构

```text
backend/   Spring Boot 后端服务
frontend/  Electron + Vue 桌面端
```

后端当前主要分层：

```text
config/
domain/
service/
websocket/
```

## 配置说明

实时识别依赖阿里云 DashScope `gummy-realtime-v1`，启动后端前必须通过环境变量配置 API Key。仓库配置文件不会提供默认密钥，避免测试密钥或生产密钥被提交。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `DASHSCOPE_API_KEY` | 空 | DashScope API Key，实时识别启动必需 |
| `aliyun.realtime.model` | `gummy-realtime-v1` | 实时识别模型 |
| `aliyun.realtime.source-language` | `en` | 源语言 |
| `aliyun.realtime.target-language` | `zh` | 目标语言 |
| `aliyun.realtime.sample-rate` | `16000` | 前端发送 PCM 采样率 |
| `aliyun.realtime.format` | `pcm` | 前后端 WebSocket 音频格式 |

## 本地开发

后端：

```powershell
cd backend
$env:DASHSCOPE_API_KEY="你的阿里云 DashScope API Key"
mvn spring-boot:run
```

前端：

```powershell
cd frontend
npm install
npm run dev
```

如需连接非本机后端，可在前端启动前配置：

```powershell
$env:VITE_SUBTITLE_WS_URL="ws://localhost:8080/ws/audio"
npm run dev
```

## 验证命令

```powershell
cd backend
mvn test

cd frontend
npm run typecheck
```
