import urllib.request, json
url = 'https://api.github.com/repos/MaaXYZ/MaaFramework/releases/latest'
req = urllib.request.Request(url, headers={'User-Agent': 'MaaFwApp-Setup'})
try:
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        print('最新版本:', data['tag_name'])
        for asset in data['assets']:
            if 'android' in asset['name']:
                print(f"  {asset['name']}: {asset['browser_download_url']}")
except Exception as e:
    print('获取失败:', e)
