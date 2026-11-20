# Schwab Trader API & Streamer v1 — Verified Engineering Reference

## 1. Base URLs & OAuth 2.0 Endpoints
* **Authorize (Browser/Custom Tab):** `https://api.schwabapi.com/v1/oauth/authorize?client_id={APP_KEY}&redirect_uri={URL_ENCODED_CALLBACK}`
* **Token Exchange & Refresh:** `POST https://api.schwabapi.com/v1/oauth/token`
  * Header: `Authorization: Basic Base64("{APP_KEY}:{APP_SECRET}")`
  * Header: `Content-Type: application/x-www-form-urlencoded`
  * Exchange Body: `grant_type=authorization_code&code={URL_DECODED_CODE}&redirect_uri={CALLBACK_URL}`
  * Refresh Body: `grant_type=refresh_token&refresh_token={REFRESH_TOKEN}`
* **Token Lifetimes:** Access token = **30 min** (1800s; auto-refresh at 25 min). Refresh token = **7 days** (hard cliff; requires interactive browser re-login).
* **Trader Base URL:** `https://api.schwabapi.com/trader/v1`
* **Market Data Base URL:** `https://api.schwabapi.com/marketdata/v1`
* **Streamer WebSocket URL:** Retrieved dynamically from `GET /trader/v1/userPreference` -> `streamerInfo[0].streamerSocketUrl` (typically `wss://streamer-api.schwab.com/ws`).
* **Rate Limit:** Strict **120 requests/minute** across REST endpoints. Enforce client-side token-bucket throttling.

## 2. Key REST Endpoints
* `GET /trader/v1/accounts/accountNumbers` -> returns `[{ "accountNumber": "...", "hashValue": "..." }]` (ALWAYS use `hashValue` in path params).
* `GET /trader/v1/accounts/{accountHash}?fields=positions` -> balances, margin BP, open positions.
* `GET /trader/v1/accounts/{accountHash}/orders?fromEnteredTime=...&toEnteredTime=...` -> working/filled orders.
* `POST /trader/v1/accounts/{accountHash}/orders` -> submit order (`201 Created`; order ID returned in `Location` header).
* `DELETE /trader/v1/accounts/{accountHash}/orders/{orderId}` -> cancel order.
* `GET /trader/v1/userPreference` -> returns `streamerInfo` (`schwabClientCustomerId`, `schwabClientCorrelId`, `schwabClientChannel`, `schwabClientFunctionId`, `streamerSocketUrl`).
* `GET /marketdata/v1/quotes?symbols=SPY,IWM,$VIX,$VIX3M&fields=quote,fundamental,reference`
* `GET /marketdata/v1/chains?symbol=IWM&contractType=ALL&includeUnderlyingQuote=true&strategy=SINGLE`
* `GET /marketdata/v1/pricehistory?symbol=SPY&periodType=year&period=1&frequencyType=daily&frequency=1`
* **Deserialization Rule:** Schwab `/chains` and `/accounts` occasionally return floats where schemas declare integers (e.g., open interest or volume). Configure Kotlinx Serialization with `ignoreUnknownKeys = true` and `isLenient = true`, and parse numeric market fields defensively as `Double?`.

## 3. OCC 21-Character Option Symbol Format
Exact length = **21 characters**: `[6-char space-padded root][YYMMDD][C|P][8-digit strike = price * 1000, zero-padded]`
* Example (`NVDA Jan 21 2028 $165 Call`): `"NVDA  280121C00165000"` (`"NVDA  "` = 6 chars + `"280121"` + `"C"` + `"00165000"`)
* Example (`IWM Dec 18 2026 $284 Call`): `"IWM   261218C00284000"` (`"IWM   "` = 6 chars + `"261218"` + `"C"` + `"00284000"`)

## 4. Verified Order JSON Payloads (`POST /trader/v1/accounts/{hash}/orders`)
*Always format `price` and `stopPrice` with exact tick precision (2 decimal places via `BigDecimal`, never raw floating-point artifacts).*

### A. Extended-Hours / Seamless Synthetic Stop Exit (Equity LIMIT)
* `session`: `"AM"` (7:00–9:25 AM ET), `"PM"` (4:05–8:00 PM ET), `"SEAMLESS"` (7:00 AM–8:00 PM ET), or `"NORMAL"` (9:30 AM–4:00 PM ET).
* Native `STOP` orders are rejected outside `NORMAL`; extended-hours exits MUST use `"orderType": "LIMIT"` and `"duration": "DAY"`.

```json
{
  "orderType": "LIMIT",
  "session": "SEAMLESS",
  "price": "269.50",
  "duration": "DAY",
  "orderStrategyType": "SINGLE",
  "orderLegCollection": [
    {
      "instruction": "SELL",
      "quantity": 16,
      "instrument": {
        "symbol": "GOOG",
        "assetType": "EQUITY"
      }
    }
  ]
}
```

### B. Multi-Leg Diagonal Spread (PMCC / PMCP)
* Use `"orderType": "NET_DEBIT"`, `"complexOrderStrategyType": "DIAGONAL"`, `"orderStrategyType": "SINGLE"`.

```json
{
  "orderType": "NET_DEBIT",
  "session": "NORMAL",
  "price": "61.36",
  "duration": "DAY",
  "complexOrderStrategyType": "DIAGONAL",
  "orderStrategyType": "SINGLE",
  "orderLegCollection": [
    {
      "instruction": "BUY_TO_OPEN",
      "quantity": 1,
      "instrument": {
        "symbol": "IWM   280121C00220000",
        "assetType": "OPTION"
      }
    },
    {
      "instruction": "SELL_TO_OPEN",
      "quantity": 1,
      "instrument": {
        "symbol": "IWM   261218C00284000",
        "assetType": "OPTION"
      }
    }
  ]
}
```

### C. Vertical Credit Spread, Calendar & Iron Condor
* **Vertical Credit Spread (Bull Put / Bear Call):** `"orderType": "NET_CREDIT"`, `"complexOrderStrategyType": "VERTICAL"`, 2 legs in `orderLegCollection`.
* **Calendar Spread:** `"orderType": "NET_DEBIT"`, `"complexOrderStrategyType": "CALENDAR"`, 2 legs in `orderLegCollection`.
* **Iron Condor:** `"orderType": "NET_CREDIT"`, `"complexOrderStrategyType": "IRON_CONDOR"`, 4 legs in `orderLegCollection`.

### D. OCO Equity Bracket (Take-Profit Limit + Hard Stop)

```json
{
  "orderStrategyType": "OCO",
  "childOrderStrategies": [
    {
      "orderType": "LIMIT",
      "session": "NORMAL",
      "price": "300.00",
      "duration": "GOOD_TILL_CANCEL",
      "orderStrategyType": "SINGLE",
      "orderLegCollection": [
        {
          "instruction": "SELL",
          "quantity": 16,
          "instrument": {
            "symbol": "GOOG",
            "assetType": "EQUITY"
          }
        }
      ]
    },
    {
      "orderType": "STOP",
      "session": "NORMAL",
      "stopPrice": "270.00",
      "duration": "GOOD_TILL_CANCEL",
      "orderStrategyType": "SINGLE",
      "orderLegCollection": [
        {
          "instruction": "SELL",
          "quantity": 16,
          "instrument": {
            "symbol": "GOOG",
            "assetType": "EQUITY"
          }
        }
      ]
    }
  ]
}
```

## 5. Schwab WebSocket Streamer Protocol & Verified Field Indices
* **Single Connection Limit:** Schwab allows **MAX 1 active WebSocket connection** per user. Error code `12` (`CLOSE_CONNECTION`) triggers if a second socket opens.
* **Commands:** `LOGIN` (`ADMIN`), `SUBS` (replaces active keys for that service), `ADD` (appends keys without wiping existing subscriptions), `UNSUBS`, `LOGOUT`.

### Login Envelope

```json
{
  "requests": [
    {
      "requestid": "1",
      "service": "ADMIN",
      "command": "LOGIN",
      "SchwabClientCustomerId": "{schwabClientCustomerId}",
      "SchwabClientCorrelId": "{schwabClientCorrelId}",
      "parameters": {
        "Authorization": "{ACCESS_TOKEN}",
        "SchwabClientChannel": "{schwabClientChannel}",
        "SchwabClientFunctionId": "{schwabClientFunctionId}"
      }
    }
  ]
}
```

### `LEVELONE_EQUITIES` Field Indices (Schwab v1 Verified)
* `0` = Symbol (`key`)
* `1` = Bid Price (`double`) — **Use for Long Stop-Loss evaluation**
* `2` = Ask Price (`double`) — **Use for Short Cover evaluation**
* `3` = Last Price (`double`)
* `4` = Bid Size (`long`)
* `5` = Ask Size (`long`)
* `8` = Total Volume (`long`)
* `9` = Last Size (`long`)
* `32` = Security Status (`String`: e.g., `"Normal"`, `"Closed"`, `"Halted"`)
* `33` = Mark Price (`double`)
* `34` = Quote Time (`long`, epoch ms)
* `35` = Trade Time (`long`, epoch ms)

### `LEVELONE_OPTIONS` Field Indices (Schwab v1 Verified)
*Note: Option field `1` is Description, NOT Bid Price.*
* `0` = Option Symbol (`key`)
* `2` = Bid Price (`double`)
* `3` = Ask Price (`double`)
* `4` = Last Price (`double`)
* `9` = Open Interest (`long`)
* `10` = Implied Volatility (`double`)
* `28` = Delta (`double`)
* `29` = Gamma (`double`)
* `30` = Theta (`double`)
* `31` = Vega (`double`)
* `32` = Rho (`double`)
* `33` = Security Status (`String`)
* `35` = Underlying Price (`double`)
* `37` = Mark Price (`double`)
* `38` = Quote Time (`long`, epoch ms)

### `ACCT_ACTIVITY` Field Indices
* `keys`: `"Account Activity"`, `fields`: `"0,1,2,3"`
* `0` = Subscription Key
* `1` = Account Number
* `2` = Message Type (`OrderAccepted`, `OrderFill`, `OrderCancelRequest`, `UROUT`, `OrderRejection`)
* `3` = Message Data (JSON string with order/execution details)