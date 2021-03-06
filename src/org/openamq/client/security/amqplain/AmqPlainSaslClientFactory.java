package org.openamq.client.security.amqplain;

import javax.security.sasl.SaslClientFactory;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import javax.security.sasl.Sasl;
import javax.security.auth.callback.CallbackHandler;
import java.util.Map;

public class AmqPlainSaslClientFactory implements SaslClientFactory
{
    public SaslClient createSaslClient(String[] mechanisms, String authorizationId, String protocol, String serverName, Map props, CallbackHandler cbh) throws SaslException
    {
        for (int i = 0; i < mechanisms.length; i++)
        {
            if (mechanisms[i].equals(AmqPlainSaslClient.MECHANISM))
            {
                if (cbh == null)
                {
                    throw new SaslException("CallbackHandler must not be null");
                }
                return new AmqPlainSaslClient(cbh);
            }
        }
        return null;
    }

    public String[] getMechanismNames(Map props)
    {
        if (props.containsKey(Sasl.POLICY_NOPLAINTEXT) ||
            props.containsKey(Sasl.POLICY_NODICTIONARY) ||
            props.containsKey(Sasl.POLICY_NOACTIVE))
        {
            // returned array must be non null according to interface documentation
            return new String[0];
        }
        else
        {
            return new String[]{AmqPlainSaslClient.MECHANISM};
        }
    }
}
