# AI Working Session 02 - Implementation

This session details the implementation guide, architectural choices, and the decision log structure for the Distributed Rate Limiter.

---

### Step 1: Project-ஐ Run பண்ணு (Run the Project)
முதலில் project run ஆகுதா என்று check பண்ணு. Run ஆன பிறகுதான் code எழுத ஆரம்பிக்கணும்.

---

### Step 2: Project Structure புரிஞ்சுக்கோ (Understand Project Structure)
உதாரணத்திற்கு:
```
src
├── controller
├── service
├── middleware/filter
├── model
└── config
```
முதலில் எந்த file request-ஐ receive பண்ணுது என்று கண்டுபிடி.

---

### Step 3: Customer Plans Create பண்ணு (Create Customer Plans)
உதாரணம்:
- **Starter** → 60 RPM
- **Growth** → 300 RPM
- **Enterprise** → 300 RPM

---

### Step 4: Token Bucket உருவாக்கு (Create Token Bucket)
நீயே algorithm invent பண்ண வேண்டாம். **Token Bucket** use பண்ணு.

#### Idea மட்டும்:
- Bucket = 300 tokens

```
Request வந்தது
     │
     ▼
Token இருக்கா?
 ┌───┴───┐
YES      NO
 │       │
▼       ▼
Allow   429
```

---

### Step 5: Redis Use பண்ணு (Use Redis)
**ஏன்?**
3 servers இருக்கின்றன (Server1, Server2, Server3). எல்லா server-க்கும் ஒரே token count தெரியணும்.
Redis-ல store பண்ணினா, எந்த server request handle பண்ணாலும் ஒரே data use ஆகும்.

---

### Step 6: Middleware எழுது (Write Middleware)
#### Flow:
```
Request வந்தது
     │
     ▼
X-Customer-Id Read
     │
     ▼
Limit கண்டுபிடி
     │
     ▼
Redis Check
     │
     ▼
Token இருக்கா?
 ┌───┴───┐
YES      NO
 │       │
▼       ▼
Controller  429 Too Many Requests
 │
▼
Response
```

---

### Step 7: Night Configuration (Night Configuration)
இங்கே கவனமாக இரு. ❌ இப்படி செய்யக்கூடாது:
```java
if (customer.equals("Northwind")) {
    limit = 1200;
}
```
இது CTO memo-க்கு எதிராக இருக்கும்.

#### அதற்கு பதிலாக:
Configuration file-ல் `2 AM–4 AM` Limit = 1200 என்று வை. Code configuration-ஐ மட்டும் படிக்கட்டும்.

---

### Step 8: Test (Test the Rate Limiter)
Test பண்ணு.

#### Customer A:
- Limit = 60
- 61st Request → **429**

#### Northwind:
- 2 AM → **1200 வரை Allow**
- Morning (300க்கு மேல்) → **429**

---

### Step 9: DECISIONS.md
இதுதான் interviewer அதிகமாக படிப்பார். இதில் கீழே உள்ளவற்றை explain பண்ணு:
- ஏன் Token Bucket?
- ஏன் Redis?
- ஏன் Configuration?
- ஏன் Hardcode செய்யவில்லை?

---

### FAQ & Interview Guide

#### உன் கேள்வி:
*"CTO 300 தான் சொல்றார். நான் policy-ல 1200 கொடுத்தா, CTO கோபப்பட மாட்டாரா?"*

#### Answer:
ஆமாம், கோபப்படலாம்... ஆனால் ஒரு condition இருக்கு.

* **Situation 1 (தவறு ❌):** CTO-க்கு தெரியாம நீ code-ல அல்லது config-ல 1200 பண்ணிட்ட. அப்போ CTO சொல்வார்: *"நான் 300 தான் limit சொன்னேன். யார் permission கொடுத்தது?"* (இது தவறு)
* **Situation 2 (சரி  ):** Business Team, CEO, Sales Team எல்லாரும் சேர்ந்து முடிவு பண்ணுறாங்க: *"Northwind நமக்கு முக்கிய customer. அவர்களுடைய contract-ஐ மாற்றலாம். இரவு 2–4 மணிக்கு 1200 RPM allow பண்ணலாம்."*

இப்போ CTO என்ன செய்வார்? அவர் சொல்லுவார்: *"சரி. Business policy change ஆயிடுச்சு. நான் code-ஐ மாற்ற மாட்டேன். Configuration-ஐ மட்டும் update பண்ணுங்க."* இங்கே CTO கோபப்பட மாட்டார்.

#### இதை ஒரு School Example-ல பார்ப்போம்:
Principal சொல்றார்: *"ஒவ்வொரு student-க்கும் 300 chocolates."*
ரமேஷ் வந்து சொல்றான்: *"எனக்கு 1200 வேணும்."*

* Teacher தனியா 1200 கொடுத்தா? ❌ Principal கோபப்படுவார்.
* ஆனா Principal meeting-ல சொல்லுறார்: *"ரமேஷ் School Captain. அவனுக்கு special permission."* இப்போ Teacher 1200 கொடுத்தா?   சரி. ஏன்னா Rule அதிகாரப்பூர்வமாக (officially) மாறியிருக்கிறது.

இதுதான் CTO memo-வின் உண்மையான அர்த்தம். CTO சொல்லவில்லை: *"Northwind-க்கு எப்போதும் 300 தான்."* CTO சொன்னது: *"Don't secretly change the rules in code."* அதாவது,
* ❌ Secret exception வேண்டாம்.
*   Official configuration change பரவாயில்லை.

#### Interview-ல interviewer உன்னிடம் கேட்டால்:
*"How would you solve the Northwind conflict?"*

#### நீ இப்படி சொல்லலாம்:
> *"I would not secretly increase Northwind's limit. If the business decides that Northwind needs a higher rate, I would update their configured policy or contract. The application simply reads the configuration, so no code changes are required."*
👉 இதுதான் senior engineers usually சொல்ற answer.

#### CTO memo-ல ஒரு line இருந்தது: *"No secret favors."* இந்த line-ஐ இப்போ நீ எப்படி புரிஞ்சிருக்க?
Code-ல secret `if (customer.equals("Northwind"))` எழுதக்கூடாது. Northwind-க்கு ஒருபோதும் code-level extra limit கொடுக்கக்கூடாது.

---

### Project Integration Workflow

**User:** `but i already build the project in antigravity`

**Agent:** That's even better. 👍
If you've already created the project in Antigravity (AI IDE), don't create another Spring Boot project. Instead, use the existing project as your base.

#### Now your workflow should be:
1. [x] Open the project in IntelliJ IDEA or VS Code.
2. [x] Make sure it builds successfully.
3. [x] Start Redis (you've already done this).
4. [x] Connect your project to Redis.
5. [x] Start implementing the rate limiter.

First, the project structure should look something like:
```
relay-rate-limiter/
│
├── pom.xml
└── src
    └── main
        ├── java
        └── resources
    └── test
```

Or, from PowerShell in your project folder, run `tree /F` or `dir` and paste the output.

Also verify:
- Is it a Spring Boot project?
- Does it have a `pom.xml` file?
- Can you run it with `mvn spring-boot:run` without errors?

Since Redis is already working, the next step is not to create a new project. The next step is to integrate Redis into your existing project and then implement:
- `RateLimiterFilter`
- `TokenBucketService`
- `CustomerConfig`

This is the most important thing to understand before writing any code.

---

### Role & Decision Making

#### Your Role
* You are **NOT** the CTO.
* You are **NOT** the Support Manager.
* You are a **Software Engineer**.
* Your job is to design and build the system.

#### Imagine this meeting:
* 👩‍💻 **CTO says:** *"No customer should exceed their configured limit."*
* 👨‍💼 **Support Manager says:** *"Northwind is our biggest customer. Please don't block them."*
* Now everyone looks at you. They ask: *"Can you build a system that solves this problem?"*

That's your role. You are not making business decisions. You don't decide:
* ❌ Who pays more.
* ❌ Which customer is important.
* ❌ Whether Northwind deserves extra requests.

Those decisions are made by the business team. You build the technical solution.

You say: *"Okay, if the business decides Northwind gets a higher limit at night, I'll build the software so that it reads that policy from configuration and applies it automatically."*
That's exactly what a software engineer does.

#### Imagine your Principal says:
*"Every student can borrow 3 books."*
The librarian says: *"Final-year students need more books for their project."*
Who decides that? The Principal.

What does the software engineer do? He builds the library system so it can store:
| Student Type | Books Allowed |
| :--- | :--- |
| Regular | 3 |
| Final Year | 10 |

The engineer doesn't decide the number 10. He only builds the system to support it.

---

### API Specifications & Architecture

#### Customer Status API (Optional Feature)
`GET /api/customers`

**Output:**
```json
[
  {
    "customerId": "starter-company",
    "plan": "STARTER",
    "normalLimit": 60,
    "currentTokens": 45,
    "status": "ACTIVE"
  },
  {
    "customerId": "growth-company",
    "plan": "GROWTH",
    "normalLimit": 300,
    "currentTokens": 250,
    "status": "ACTIVE"
  },
  {
    "customerId": "northwind",
    "plan": "ENTERPRISE",
    "normalLimit": 300,
    "specialLimit": 1200,
    "specialWindow": "02:00-04:00",
    "currentTokens": 900,
    "status": "ACTIVE"
  }
]
```

#### How it works internally:
```
Admin/User
    │
    ▼
GET /api/customers
    │
    ▼
CustomerPolicyService
    │
    ├───────────────┐
    ▼               ▼
PostgreSQL        Redis
 (policy)     (live tokens)
    │
    ▼
Return customer status
```

#### Core Rate Limiter API (Mandatory Requirement)

##### Allowed Request:
`HTTP 200 OK`
```json
{
  "message": "Request allowed"
}
```

##### Exceeded Request:
`HTTP 429 Too Many Requests`
```json
{
  "message": "Rate limit exceeded"
}
```

---

### Focus Summary for the Assignment
For this hiring assignment, keep the customer status API as an optional feature. The main focus should be:
1. [x] Customer policy management
2. [x] Token Bucket algorithm
3. [x] Redis distributed state
4. [x] Correct 200/429 behavior
5. [x] Multi-server testing

A dashboard showing all customers is nice, but it is not the core requirement.
