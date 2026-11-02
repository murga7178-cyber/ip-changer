# Auto IP Changer

देश और समय अंतराल चुनकर ऑटोमैटिक IP बदलने वाला वेब ऐप।

## चलाने के तरीके

### स्थानीय (Local)
pip install -r requirements.txt
python app.py
ब्राउज़र में खोलें: http://localhost:5000

### Render.com पर होस्ट करें
1. Render.com पर साइन-अप करें
2. New → Web Service → GitHub रिपो कनेक्ट करें
3. Build Command: `pip install -r requirements.txt`
4. Start Command: `gunicorn app:app`
5. फ्री प्लान पर डिप्लॉय करें
