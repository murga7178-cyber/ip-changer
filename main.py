from kivy.app import App
from kivy.uix.label import Label

class TestApp(App):
    def build(self):
        return Label(text="Hello! APK works!", font_size=40)

TestApp().run()
