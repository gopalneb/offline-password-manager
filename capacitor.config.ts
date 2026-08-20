import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'in.pocketvault.local',
  appName: 'Pocket Vault',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
  },
}

export default config
