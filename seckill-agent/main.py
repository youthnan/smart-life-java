import os
import re
from collections import defaultdict, deque
from typing import Deque, Dict, List, Literal, Optional, Tuple

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI
from pydantic import BaseModel, Field

load_dotenv()

Role = Literal["user", "assistant"]


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=4000)
    sessionId: Optional[str] = Field(default="guest", max_length=128)


class ChatResponse(BaseModel):
    answer: str
    sessionId: str


app = FastAPI(title="seckill-agent", version="0.1.0")


_history: Dict[str, Deque[Tuple[Role, str]]] = defaultdict(lambda: deque(maxlen=8))


def _norm(s: str) -> str:
    return (s or "").strip()


def _cap_text(s: str, max_len: int = 300) -> str:
    t = _norm(s)
    return t if len(t) <= max_len else t[:max_len] + "..."


def _classify_intent(text: str) -> str:
    t = text.lower()
    rules = [
        ("map_coupon", r"(附近|周边|周围).*(可抢券|优惠券|代金券|券)"),
        ("map_reco", r"(附近|周边|周围).*(推荐|商户|店铺|吃|美食)"),
        ("coupon_query", r"((优惠券|代金券|券).*(查询|看看|有吗|还有吗|可用|能用)|((查询|查|看看).*(优惠券|代金券|券)))"),
        ("merchant_reco", r"(推荐|商户|店铺|吃什么|去哪吃|附近)"),
        ("time", r"(开始|什么时候|时间|几点|end|结束|deadline)"),
        ("stock", r"(库存|没了|抢完|抢不到|售罄|不足|stock)"),
        ("fail", r"(失败|报错|错误|异常|error|503|500|404|401|429)"),
        ("repeat", r"(重复|下单多次|幂等|重复下单|重复支付)"),
        ("queue", r"(排队|等待|队列|卡住|一直转|loading|转圈|限流|拥挤|太火)"),
        ("pay", r"(支付|付款|订单|order|取消|超时未支付)"),
        ("rule", r"(规则|怎么抢|流程|步骤|注意事项)"),
        ("tech", r"(缓存|redis|锁|lua|stream|mq|热点|击穿|雪崩|穿透)"),
    ]
    for intent, pat in rules:
        if re.search(pat, t):
            return intent
    return "general"


def _format_reply(reason: str, checklist: List[str], suggestion: str) -> str:
    lines = ["【可能原因】" + reason, "【建议操作】"]
    for i, item in enumerate(checklist[:3], 1):
        lines.append(f"{i}. {item}")
    lines.append("【下一步】" + suggestion)
    return "\n".join(lines)


def _rule_answer(intent: str, text: str) -> str:
    short_text = _cap_text(text)
    if intent == "time":
        return _format_reply(
            "活动尚未开始或已结束，服务端按券时间窗口严格校验。",
            [
                "确认活动的开始/结束时间与当前时间（建议自动校时）。",
                "进入店铺详情页刷新券信息，避免使用旧页面缓存。",
                "若刚到开抢点仍失败，等待 1-2 秒后重试。"
            ],
            "把你看到的具体提示文案发我，我帮你判断是未开始还是已结束。"
        )
    if intent == "stock":
        return _format_reply(
            "库存可能已售罄，或并发高峰下预扣库存已耗尽。",
            [
                "刷新券列表确认是否仍展示可抢。",
                "检查是否同券已下单成功（会触发一人一单限制）。",
                "避开高峰连续狂点，间隔 3-5 秒重试。"
            ],
            "如果持续出现库存不足，可联系客服并说明券信息与发生时间。"
        )
    if intent == "queue":
        return _format_reply(
            "当前可能进入排队/限流通道，系统在做高峰保护。",
            [
                "保持页面稳定，不要频繁刷新或重复提交。",
                "优先使用稳定网络，避免在弱网环境抢购。",
                "等待 5-10 秒后再次尝试。"
            ],
            "如果持续排队较久，建议稍后再试或更换时间段参与。"
        )
    if intent == "repeat":
        return _format_reply(
            "系统通常限制同一用户同一券仅能成功一次（幂等控制）。",
            [
                "先确认该券是否已成功下过单。",
                "检查是否多设备使用同一账号重复提交。",
                "若提示重复下单，优先查询订单状态。"
            ],
            "把报错文案贴我，我帮你判断是幂等命中还是订单状态异常。"
        )
    if intent == "pay":
        return _format_reply(
            "订单可能已创建但支付未完成，或支付已超时关闭。",
            [
                "先确认订单是否创建成功，再处理支付问题。",
                "检查支付通道是否超时或被风控拦截。",
                "超时关闭后通常会回补库存。"
            ],
            "若方便，发一下订单状态和支付报错信息，我给你下一步建议。"
        )
    if intent == "tech":
        return (
            "这场活动采用了高峰保护策略，核心目标是保证公平和稳定。\n"
            "对用户来说，重点是：按活动时间参与、保持登录、避免重复提交、留意订单状态。"
        )
    if intent == "fail":
        return _format_reply(
            "下单失败通常与活动时间、库存竞争或系统繁忙有关。",
            [
                "先确认你已登录，且活动仍在可抢时间内。",
                "刷新券详情后重试一次，避免使用旧页面数据。",
                "如果连续失败，间隔 5-10 秒再试，避开高峰拥挤。"
            ],
            "如果仍失败，建议到订单页确认是否已下单成功，再决定是否继续尝试。"
        )
    if intent == "rule":
        return (
            "秒杀建议：\n"
            "1) 提前登录并校准时间；\n"
            "2) 开抢前停留在券详情页；\n"
            "3) 开始后尽快点击，避免重复狂点；\n"
            "4) 遇到繁忙间隔几秒重试。"
        )
    if intent == "merchant_reco":
        return (
            "可以的，我能按你的偏好推荐商户。\n"
            "你可以告诉我关键词，比如：火锅、奶茶、烧烤，或直接说商圈/区域。"
        )
    if intent == "coupon_query":
        return (
            "可以帮你查优惠券。\n"
            "请告诉我店铺名（例如：海底捞），我会返回这家店当前可查看的券信息。"
        )
    return (
        "我可以协助处理秒杀问题：时间、库存、下单失败、排队、重复下单、支付异常。\n"
        f"你刚刚说的是：{short_text}\n"
        "你可以把看到的提示文案发我，我会给你更准确的处理建议。"
    )


def _llm_enabled() -> bool:
    return bool(os.getenv("LLM_API_KEY") and os.getenv("LLM_BASE_URL") and os.getenv("LLM_MODEL"))


async def _call_openai_compatible(messages: List[dict]) -> Optional[str]:
    base_url = os.getenv("LLM_BASE_URL", "").rstrip("/")
    api_key = os.getenv("LLM_API_KEY", "")
    model = os.getenv("LLM_MODEL", "")
    timeout_s = float(os.getenv("LLM_TIMEOUT_SECONDS", "6") or "6")

    if not (base_url and api_key and model):
        return None

    url = f"{base_url}/v1/chat/completions"
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    payload = {
        "model": model,
        "messages": messages,
        "temperature": 0.4,
    }

    async with httpx.AsyncClient(timeout=timeout_s) as client:
        r = await client.post(url, headers=headers, json=payload)
        r.raise_for_status()
        data = r.json()
        return (
            (data.get("choices") or [{}])[0]
            .get("message", {})
            .get("content")
        )


def _backend_base_url() -> str:
    return os.getenv("BACKEND_BASE_URL", "http://127.0.0.1:8088").rstrip("/")


def _backend_timeout_s() -> float:
    return float(os.getenv("BACKEND_TIMEOUT_SECONDS", "5") or "5")


async def _backend_get(path: str, params: Optional[dict] = None) -> Optional[dict]:
    url = f"{_backend_base_url()}{path}"
    async with httpx.AsyncClient(timeout=_backend_timeout_s()) as client:
        resp = await client.get(url, params=params)
        resp.raise_for_status()
        data = resp.json()
        if not isinstance(data, dict):
            return None
        return data


def _extract_shop_keyword(text: str) -> str:
    t = _norm(text)
    patterns = [
        r"(?:推荐|查|查询|找)(.+?)(?:商户|店|店铺|优惠券|券)?$",
        r"(?:我想吃|想吃|想喝)(.+)$",
        r"(.+?)(?:有优惠券吗|有券吗|还有券吗|有什么券)$",
    ]
    for p in patterns:
        m = re.search(p, t)
        if m:
            kw = _norm(m.group(1))
            if kw:
                return kw
    kw = re.sub(r"(推荐|商户|店铺|店|优惠券|代金券|查询|看看|还有吗|有吗|可用)", "", t)
    return _norm(kw)


def _extract_coordinate(text: str) -> Tuple[float, float]:
    nums = re.findall(r"-?\d+\.\d+", text)
    if len(nums) >= 2:
        return float(nums[0]), float(nums[1])
    default_x = float(os.getenv("DEFAULT_MAP_X", "120.149993"))
    default_y = float(os.getenv("DEFAULT_MAP_Y", "30.334229"))
    return default_x, default_y


def _extract_radius(text: str) -> float:
    m = re.search(r"(\d{2,5})\s*(米|m|M)", text)
    if m:
        return float(m.group(1))
    km = re.search(r"(\d{1,2}(?:\.\d+)?)\s*(公里|km|KM|Km)", text)
    if km:
        return float(km.group(1)) * 1000
    return float(os.getenv("DEFAULT_MAP_RADIUS", "2000"))


def _fmt_shop_line(shop: dict) -> str:
    name = shop.get("name") or "未知商户"
    area = shop.get("area") or "未知区域"
    avg = shop.get("avgPrice")
    score = shop.get("score")
    avg_text = f"人均约{avg}元" if avg else "人均待补充"
    score_text = f"评分{round(float(score) / 10, 1)}" if score else "评分待补充"
    return f"{name}（{area}，{avg_text}，{score_text}）"


def _fmt_coupon_line(v: dict) -> str:
    title = v.get("title") or "优惠券"
    pay = v.get("payValue")
    actual = v.get("actualValue")
    stock = v.get("stock")
    if pay and actual:
        price_text = f"到手约{pay}元（原价{actual}元）"
    elif pay:
        price_text = f"到手约{pay}元"
    else:
        price_text = "价格以页面显示为准"
    stock_text = f"，剩余{stock}张" if stock is not None else ""
    return f"{title}：{price_text}{stock_text}"


async def _reply_merchant_recommend(text: str) -> str:
    keyword = _extract_shop_keyword(text)
    if not keyword:
        return "可以的，请告诉我你想吃什么或店名关键词，比如“火锅”“奶茶”“烤肉”。"
    try:
        resp = await _backend_get("/shop/of/name", {"name": keyword, "current": 1})
    except Exception:
        return "暂时没查到推荐结果，请稍后再试，或换个关键词试试。"
    shops = (resp or {}).get("data") or []
    if not shops:
        return f"暂时没找到和“{keyword}”相关的商户，你可以换个关键词，我继续帮你找。"
    lines = [f"给你推荐这几家“{keyword}”相关商户："]
    for idx, s in enumerate(shops[:3], 1):
        lines.append(f"{idx}. {_fmt_shop_line(s)}")
    lines.append("你想看哪一家的券？回复店名我可以继续帮你查。")
    return "\n".join(lines)


async def _reply_coupon_query(text: str) -> str:
    keyword = _extract_shop_keyword(text)
    if not keyword:
        return "可以帮你查券，请告诉我店铺名，比如“海底捞”或“奈雪”。"
    try:
        shop_resp = await _backend_get("/shop/of/name", {"name": keyword, "current": 1})
    except Exception:
        return "暂时没法查询优惠券，请稍后再试。"
    shops = (shop_resp or {}).get("data") or []
    if not shops:
        return f"没找到“{keyword}”这家店，你可以提供更完整店名，我再帮你查。"
    shop = shops[0]
    shop_id = shop.get("id")
    shop_name = shop.get("name") or keyword
    if not shop_id:
        return f"找到了“{shop_name}”，但暂时无法读取券信息，请稍后再试。"
    try:
        coupon_resp = await _backend_get(f"/voucher/list/{shop_id}")
    except Exception:
        return f"“{shop_name}”的优惠券暂时查询失败，请稍后再试。"
    vouchers = (coupon_resp or {}).get("data") or []
    if not vouchers:
        return f"“{shop_name}”当前暂未查询到可展示优惠券，你可以稍后刷新再看。"
    lines = [f"“{shop_name}”当前可查看到这些券："]
    for idx, v in enumerate(vouchers[:3], 1):
        lines.append(f"{idx}. {_fmt_coupon_line(v)}")
    lines.append("更多详情以店铺页展示为准。")
    return "\n".join(lines)


async def _reply_map_recommend(text: str) -> str:
    x, y = _extract_coordinate(text)
    radius = _extract_radius(text)
    keyword = _extract_shop_keyword(text)
    try:
        resp = await _backend_get("/shop/nearby/recommend", {
            "x": x, "y": y, "current": 1, "radius": radius, "keyword": keyword
        })
    except Exception:
        return "暂时无法查询附近商户，请稍后再试。"
    shops = (resp or {}).get("data") or []
    if not shops:
        return "附近暂时没找到匹配商户，你可以换个关键词或扩大范围再试。"
    lines = ["附近推荐商户如下："]
    for idx, s in enumerate(shops[:3], 1):
        dist = s.get("distance")
        if dist is None:
            dist_text = "距离未知"
        elif dist < 1000:
            dist_text = f"{dist:.0f}m"
        else:
            dist_text = f"{dist/1000:.1f}km"
        lines.append(f"{idx}. {s.get('name', '未知商户')}（{s.get('area', '未知区域')}，约{dist_text}）")
    lines.append("你想查其中哪家的优惠券？回复店名即可。")
    return "\n".join(lines)


async def _reply_map_coupon(text: str) -> str:
    x, y = _extract_coordinate(text)
    radius = _extract_radius(text)
    keyword = _extract_shop_keyword(text)
    try:
        resp = await _backend_get("/voucher/nearby/available", {
            "x": x, "y": y, "current": 1, "radius": radius, "keyword": keyword
        })
    except Exception:
        return "暂时无法查询附近可抢券，请稍后再试。"
    vouchers = (resp or {}).get("data") or []
    if not vouchers:
        return "附近暂时没查到可抢优惠券，你可以稍后再试或扩大范围。"
    lines = ["附近可抢券如下："]
    for idx, v in enumerate(vouchers[:3], 1):
        dist = v.get("distance")
        if dist is None:
            dist_text = "距离未知"
        elif dist < 1000:
            dist_text = f"{dist:.0f}m"
        else:
            dist_text = f"{dist/1000:.1f}km"
        title = v.get("title", "优惠券")
        pay = v.get("payValue")
        actual = v.get("actualValue")
        if pay and actual:
            price_text = f"{pay}元（原价{actual}元）"
        elif pay:
            price_text = f"{pay}元"
        else:
            price_text = "价格以页面显示为准"
        lines.append(
            f"{idx}. {v.get('shopName', '未知商户')} - {title}，到手约{price_text}，约{dist_text}"
        )
    lines.append("你想先看哪一家？我可以继续帮你细看券规则。")
    return "\n".join(lines)


def _system_prompt() -> str:
    return (
        "你是“问一问-秒杀助手”，只回答与秒杀/抢券/高并发下单相关的问题。\n"
        "要求：\n"
        "- 默认使用用户视角，不主动抛状态码或开发术语。\n"
        "- 优先给可操作建议（登录态、时间窗口、库存、排队、订单状态）。\n"
        "- 严禁输出日志、接口、端口、网关、状态码等技术排障内容。\n"
        "- 不要编造不存在的接口或页面；不确定时先询问关键信息。\n"
        "- 回复控制在120字左右，必要时使用“可能原因/建议操作/下一步”。\n"
        "- 用中文、友好但专业。"
    )


@app.post("/api/chat", response_model=ChatResponse)
async def chat(req: ChatRequest) -> ChatResponse:
    session_id = _norm(req.sessionId) or "guest"
    text = _norm(req.message)

    intent = _classify_intent(text)
    h = _history[session_id]
    h.append(("user", text))

    if intent == "merchant_reco":
        answer = await _reply_merchant_recommend(text)
        h.append(("assistant", answer))
        return ChatResponse(answer=answer, sessionId=session_id)
    if intent == "coupon_query":
        answer = await _reply_coupon_query(text)
        h.append(("assistant", answer))
        return ChatResponse(answer=answer, sessionId=session_id)
    if intent == "map_reco":
        answer = await _reply_map_recommend(text)
        h.append(("assistant", answer))
        return ChatResponse(answer=answer, sessionId=session_id)
    if intent == "map_coupon":
        answer = await _reply_map_coupon(text)
        h.append(("assistant", answer))
        return ChatResponse(answer=answer, sessionId=session_id)

    fallback = _rule_answer(intent, text)
    if not _llm_enabled():
        h.append(("assistant", fallback))
        return ChatResponse(answer=fallback, sessionId=session_id)

    recent = list(h)
    messages = [{"role": "system", "content": _system_prompt()}]
    for role, content in recent[-6:]:
        messages.append({"role": "user" if role == "user" else "assistant", "content": content})
    messages.append({"role": "system", "content": f"如果无法确定，请使用以下兜底内容：\n{fallback}"})

    try:
        answer = await _call_openai_compatible(messages)
        answer = _norm(answer) or fallback
    except Exception:
        answer = fallback

    h.append(("assistant", answer))
    return ChatResponse(answer=answer, sessionId=session_id)


@app.get("/healthz")
async def healthz():
    return {"ok": True}

