package br.com.caelum.camel.start;

import org.apache.activemq.camel.component.ActiveMQComponent;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http4.HttpMethods;
import org.apache.camel.impl.DefaultCamelContext;

public class RotaPedidos {

	public static void main(String... args) throws Exception {
		
		CamelContext context = new DefaultCamelContext();
		
		context.addComponent("activemq", ActiveMQComponent.activeMQComponent("tcp://localhost:61616/"));
		context.addRoutes(new RouteBuilder() {
			
			@Override
			public void configure() throws Exception {
				/*Rota para erros: Existem mais forma de tratar os error*/
				errorHandler(deadLetterChannel("activemq:queue:pedidos.DLQ").
						logExhaustedMessageHistory(true).//motrando log
						maximumRedeliveries(3).redeliveryDelay(2000).//reenviando por 3x e acada 2 segundos
							onRedelivery(new Processor() {
								@Override
								public void process(Exchange exchange) throws Exception {
									int counter = (int) exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER);
									int max = (int) exchange.getIn().getHeader(Exchange.REDELIVERY_MAX_COUNTER);
									System.out.println("Redelivery..." + counter +"/"+ max);
								}
							}));
				
				/*Lendo xml de uma MQ*/
				from("activemq:queue:pedidos").routeId("rota-pedidos").
					to("validator:pedido.xsd").//espalhando a mesma mensagem para as rotas
					multicast().
					 	to("direct:soap").
					 	to("direct:http");
				
				/*Rota http*/
				from("direct:http").
					setProperty(("pedidoId"), xpath("/pedido/id/text()")).
					setProperty(("clienteId"), xpath("/pedido/pagamento/email-titular/text()")).
					split().
						xpath("/pedido/itens/item").
					filter().
						xpath("/item/formato[text()='EBOOK']").
					setProperty("ebookId",xpath("/item/livro/codigo/text()")).	
					marshal().xmljson().
					setHeader(Exchange.HTTP_METHOD, HttpMethods.GET).
					setHeader(Exchange.HTTP_QUERY, simple("ebookId=${property.ebookId}&pedidoId=${property.pedidoId}&clienteId=${property.clienteId}")).
				to("http4://localhost:8080/webservices/ebook/item");
				
				
				/*Rota soap*/
				from("direct:soap").routeId("rota-soap").
					to("xslt:pedido-para-soap.xslt").
					log("${body}").
					setHeader(Exchange.CONTENT_TYPE, constant("text/xml")).
				to("http4://localhost:8080/webservices/financeiro");
			}
		});
		
		context.start();
		Thread.sleep(20000);
		context.stop();
	}
}
