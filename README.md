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

后端保留基础分层目录：

```text
config/
controller/
domain/
repository/
service/
```

## 本地开发

后端：

```powershell
cd backend
$env:DASHSCOPE_API_KEY="你的阿里云 DashScope API Key"
mvn spring-boot:run
```

实时识别依赖阿里云 `gummy-realtime-v1`，后端启动时必须配置 `DASHSCOPE_API_KEY`。如果没有配置，前端仍会保持 WebSocket 连接，但 AI 状态会显示异常并提示缺少 API Key。

前端：

```powershell
cd frontend
npm install
npm run dev
```
