import { useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View
} from 'react-native';
import { missingEnv } from '../config';
import { supabase } from '../lib/supabase';

export function LoginScreen() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const missing = missingEnv();

  async function handleLogin() {
    setSubmitting(true);
    setError(null);
    const { error: signInError } = await supabase.auth.signInWithPassword({
      email: email.trim(),
      password
    });
    if (signInError) setError(signInError.message);
    setSubmitting(false);
  }

  if (missing.length > 0) {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>缺少配置</Text>
        <Text style={styles.hint}>
          请在 apps/mobile/.env 中填写：{missing.join('、')}，然后重启 Expo。
        </Text>
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <Text style={styles.title}>膳食指南 AI</Text>
      <Text style={styles.hint}>使用 Supabase 账号登录</Text>

      <TextInput
        style={styles.input}
        value={email}
        onChangeText={setEmail}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
        placeholder="邮箱"
        placeholderTextColor="#9aa0a6"
      />
      <TextInput
        style={styles.input}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        placeholder="密码"
        placeholderTextColor="#9aa0a6"
      />

      {error ? <Text style={styles.error}>{error}</Text> : null}

      <Pressable
        style={[styles.button, submitting && styles.buttonDisabled]}
        onPress={handleLogin}
        disabled={submitting || !email || !password}
      >
        {submitting ? (
          <ActivityIndicator color="#ffffff" />
        ) : (
          <Text style={styles.buttonText}>登录</Text>
        )}
      </Pressable>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 24, backgroundColor: '#ffffff' },
  title: { fontSize: 24, fontWeight: '600', color: '#1f2933' },
  hint: { marginTop: 8, marginBottom: 24, fontSize: 14, color: '#52606d' },
  input: {
    height: 48,
    borderWidth: 1,
    borderColor: '#d9e2ec',
    borderRadius: 8,
    paddingHorizontal: 12,
    marginBottom: 12,
    color: '#1f2933'
  },
  error: { color: '#c0392b', marginBottom: 12 },
  button: {
    height: 48,
    borderRadius: 8,
    backgroundColor: '#2f855a',
    alignItems: 'center',
    justifyContent: 'center'
  },
  buttonDisabled: { opacity: 0.6 },
  buttonText: { color: '#ffffff', fontSize: 16, fontWeight: '600' }
});
